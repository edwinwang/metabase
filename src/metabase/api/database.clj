(ns metabase.api.database
  "/api/database endpoints."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [compojure.core :refer [DELETE GET POST PUT]]
            [medley.core :as m]
            [metabase.api.common :as api]
            [metabase.api.table :as table-api]
            [metabase.config :as config]
            [metabase.driver :as driver]
            [metabase.driver.util :as driver.u]
            [metabase.events :as events]
            [metabase.mbql.schema :as mbql.s]
            [metabase.mbql.util :as mbql.u]
            [metabase.models.card :refer [Card]]
            [metabase.models.collection :as collection :refer [Collection]]
            [metabase.models.database :as database :refer [Database protected-password]]
            [metabase.models.field :refer [Field readable-fields-only]]
            [metabase.models.field-values :refer [FieldValues]]
            [metabase.models.interface :as mi]
            [metabase.models.permissions :as perms]
            [metabase.models.table :refer [Table]]
            [metabase.models.task-history :refer [TaskHistory]]
            [metabase.public-settings :as public-settings]
            [metabase.sample-data :as sample-data]
            [metabase.server.middleware.offset-paging :as offset-paging]
            [metabase.sync.analyze :as analyze]
            [metabase.sync.field-values :as sync-field-values]
            [metabase.sync.schedules :as sync.schedules]
            [metabase.sync.sync-metadata :as sync-metadata]
            [metabase.util :as u]
            [metabase.util.cron :as cron-util]
            [metabase.util.i18n :refer [deferred-tru trs tru]]
            [metabase.util.schema :as su]
            [schema.core :as s]
            [toucan.db :as db]
            [toucan.hydrate :refer [hydrate]])
  (:import metabase.models.database.DatabaseInstance))

(def DBEngineString
  "Schema for a valid database engine name, e.g. `h2` or `postgres`."
  (su/with-api-error-message (s/constrained
                              su/NonBlankString
                              #(u/ignore-exceptions (driver/the-driver %))
                              "Valid database engine")
    (deferred-tru "value must be a valid database engine.")))


;;; ----------------------------------------------- GET /api/database ------------------------------------------------

(defn- add-tables [dbs]
  (let [db-id->tables (group-by :db_id (filter mi/can-read? (db/select Table
                                                              :active          true
                                                              :db_id           [:in (map :id dbs)]
                                                              :visibility_type nil
                                                              {:order-by [[:%lower.schema :asc]
                                                                          [:%lower.display_name :asc]]})))]
    (for [db dbs]
      (assoc db :tables (get db-id->tables (:id db) [])))))

(s/defn ^:private add-native-perms-info :- [{:native_permissions (s/enum :write :none), s/Keyword s/Any}]
  "For each database in DBS add a `:native_permissions` field describing the current user's permissions for running
  native (e.g. SQL) queries. Will be either `:write` or `:none`. `:write` means you can run ad-hoc native queries,
  and save new Cards with native queries; `:none` means you can do neither.

  For the curious: the use of `:write` and `:none` is mainly for legacy purposes, when we had data-access-based
  permissions; there was a specific option where you could give a Perms Group permissions to run existing Cards with
  native queries, but not to create new ones. With the advent of what is currently being called 'Space-Age
  Permissions', all Cards' permissions are based on their parent Collection, removing the need for native read perms."
  [dbs :- [su/Map]]
  (for [db dbs]
    (assoc db :native_permissions (if (perms/set-has-full-permissions? @api/*current-user-permissions-set*
                                        (perms/adhoc-native-query-path (u/get-id db)))
                                    :write
                                    :none))))

(defn- card-database-supports-nested-queries? [{{database-id :database} :dataset_query, :as card}]
  (when database-id
    (when-let [driver (driver.u/database->driver database-id)]
      (driver/supports? driver :nested-queries))))

(defn- card-has-ambiguous-columns?
  "We know a card has ambiguous columns if any of the columns that come back end in `_2` (etc.) because that's what
   clojure.java.jdbc 'helpfully' does for us automatically.
   Presence of ambiguous columns disqualifies a query for use as a source query because something like

     SELECT name
     FROM (
       SELECT x.name, y.name
       FROM x
       LEFT JOIN y on x.id = y.id
     )

   would be ambiguous. Too many things break when attempting to use a query like this. In the future, this may be
   supported, but it will likely require rewriting the source SQL query to add appropriate aliases (this is even
   trickier if the source query uses `SELECT *`)."
  [{result-metadata :result_metadata, dataset-query :dataset_query}]
  (and (= (:type dataset-query) :native)
       (some (partial re-find #"_2$")
             (map (comp name :name) result-metadata))))

(defn- card-uses-unnestable-aggregation?
  "Since cumulative count and cumulative sum aggregations are done in Clojure-land we can't use Cards that use queries
  with those aggregations as source queries. This function determines whether `card` is using one of those queries so
  we can filter it out in Clojure-land."
  [{{{aggregations :aggregation} :query} :dataset_query}]
  (mbql.u/match aggregations #{:cum-count :cum-sum}))

(defn card-can-be-used-as-source-query?
  "Does `card`'s query meet the conditions required for it to be used as a source query for another query?"
  [card]
  (and (card-database-supports-nested-queries? card)
       (not (or (card-uses-unnestable-aggregation? card)
                (card-has-ambiguous-columns? card)))))

(defn- ids-of-dbs-that-support-source-queries []
  (set (filter (fn [db-id]
                 (try
                   (some-> (driver.u/database->driver db-id) (driver/supports? :nested-queries))
                   (catch Throwable e
                     (log/error e (tru "Error determining whether Database supports nested queries")))))
               (db/select-ids Database))))

(defn- source-query-cards
  "Fetch the Cards that can be used as source queries (e.g. presented as virtual tables)."
  [& {:keys [additional-constraints xform], :or {xform identity}}]
  (when-let [ids-of-dbs-that-support-source-queries (not-empty (ids-of-dbs-that-support-source-queries))]
    (transduce
     (comp (filter card-can-be-used-as-source-query?) xform)
     (completing conj #(hydrate % :collection))
     []
     (db/select-reducible [Card :name :description :database_id :dataset_query :id :collection_id :result_metadata]
       {:where    (into [:and
                         [:not= :result_metadata nil]
                         [:= :archived false]
                         [:in :database_id ids-of-dbs-that-support-source-queries]
                         (collection/visible-collection-ids->honeysql-filter-clause
                          (collection/permissions-set->visible-collection-ids @api/*current-user-permissions-set*))]
                        additional-constraints)
       :order-by [[:%lower.name :asc]]}))))

(defn- source-query-cards-exist?
  "Truthy if a single Card that can be used as a source query exists."
  []
  (seq (source-query-cards :xform (take 1))))

(defn- cards-virtual-tables
  "Return a sequence of 'virtual' Table metadata for eligible Cards.
   (This takes the Cards from `source-query-cards` and returns them in a format suitable for consumption by the Query
   Builder.)"
  [& {:keys [include-fields?]}]
  (for [card (source-query-cards)]
    (table-api/card->virtual-table card :include-fields? include-fields?)))

(defn- saved-cards-virtual-db-metadata [& {:keys [include-tables? include-fields?]}]
  (when (public-settings/enable-nested-queries)
    (cond-> {:name               "Saved Questions"
             :id                 mbql.s/saved-questions-virtual-database-id
             :features           #{:basic-aggregations}
             :is_saved_questions true}
      include-tables? (assoc :tables (cards-virtual-tables :include-fields? include-fields?)))))

;; "Virtual" tables for saved cards simulate the db->schema->table hierarchy by doing fake-db->collection->card
(defn- add-saved-questions-virtual-database [dbs & options]
  (let [virtual-db-metadata (apply saved-cards-virtual-db-metadata options)]
    ;; only add the 'Saved Questions' DB if there are Cards that can be used
    (cond-> dbs
      (and (source-query-cards-exist?) virtual-db-metadata) (concat [virtual-db-metadata]))))

(defn- dbs-list [& {:keys [include-tables?
                           include-saved-questions-db?
                           include-saved-questions-tables?
                           limit
                           offset]}]
  (when-let [dbs (seq (filter mi/can-read? (db/select Database
                                                      {:order-by [:%lower.name :%lower.engine]
                                                       :limit limit
                                                       :offset offset})))]
    (cond-> (add-native-perms-info dbs)
      include-tables?             add-tables
      include-saved-questions-db? (add-saved-questions-virtual-database :include-tables? include-saved-questions-tables?))))

(def FetchAllIncludeValues
  "Schema for matching the include parameter of the GET / endpoint"
  (su/with-api-error-message
    (s/maybe (s/eq "tables"))
    (deferred-tru "include must be either empty or the value 'tables'")))

(api/defendpoint GET "/"
  "Fetch all `Databases`.

  * `include=tables` means we should hydrate the Tables belonging to each DB. Default: `false`.

  * `saved` means we should include the saved questions virtual database. Default: `false`.

  * `include_tables` is a legacy alias for `include=tables`, but should be considered deprecated as of 0.35.0, and will
    be removed in a future release.

  * `include_cards` here means we should also include virtual Table entries for saved Questions, e.g. so we can easily
    use them as source Tables in queries. This is a deprecated alias for `saved=true` + `include=tables` (for the saved
    questions virtual DB). Prefer using `include` and `saved` instead. "
  [include_tables include_cards include saved]
  {include_tables (s/maybe su/BooleanString)
   include_cards  (s/maybe su/BooleanString)
   include        FetchAllIncludeValues
   saved          (s/maybe su/BooleanString)}
  (when (and config/is-dev?
             (or include_tables include_cards))
    ;; don't need to i18n since this is dev-facing only
    (log/warn "GET /api/database?include_tables and ?include_cards are deprecated."
              "Prefer using ?include=tables and ?saved=true instead."))
  (let [include-tables?                 (cond
                                          (seq include)        (= include "tables")
                                          (seq include_tables) (Boolean/parseBoolean include_tables))
        include-saved-questions-db?     (cond
                                          (seq saved)         (Boolean/parseBoolean saved)
                                          (seq include_cards) (Boolean/parseBoolean include_cards))
        include-saved-questions-tables? (when include-saved-questions-db?
                                          (if (seq include_cards)
                                            true
                                            include-tables?))]
    {:data  (or (dbs-list :include-tables?                  include-tables?
                          :include-saved-questions-db?      include-saved-questions-db?
                          :include-saved-questions-tables?  include-saved-questions-tables?
                          :limit                            offset-paging/*limit*
                          :offset                           offset-paging/*offset*)
                [])
     :limit  offset-paging/*limit*
     :offset offset-paging/*offset*
     :total  (db/count Database)}))


;;; --------------------------------------------- GET /api/database/:id ----------------------------------------------

(s/defn ^:private expanded-schedules [db :- DatabaseInstance]
  {:cache_field_values (cron-util/cron-string->schedule-map (:cache_field_values_schedule db))
   :metadata_sync      (cron-util/cron-string->schedule-map (:metadata_sync_schedule db))})

(defn- add-expanded-schedules
  "Add 'expanded' versions of the cron schedules strings for DB in a format that is appropriate for frontend
  consumption."
  [db]
  (assoc db :schedules (expanded-schedules db)))

(defn- filter-sensitive-fields
  [fields]
  (remove #(= :sensitive (:visibility_type %)) fields))

(defn- get-database-hydrate-include
  "If URL param `?include=` was passed to `GET /api/database/:id`, hydrate the Database appropriately."
  [db include]
  (if-not include
    db
    (-> (hydrate db (case include
                      "tables"        :tables
                      "tables.fields" [:tables [:fields [:target :has_field_values] :has_field_values]]))
        (update :tables (fn [tables]
                          (cond->> tables
                            ; filter hidden tables
                            true                        (filter (every-pred (complement :visibility_type) mi/can-read?))
                            ; filter hidden fields
                            (= include "tables.fields") (map #(update % :fields filter-sensitive-fields))))))))

(api/defendpoint GET "/:id"
  "Get a single Database with `id`. Optionally pass `?include=tables` or `?include=tables.fields` to include the Tables
  belonging to this database, or the Tables and Fields, respectively."
  [id include]
  {include (s/maybe (s/enum "tables" "tables.fields"))}
  (-> (api/read-check Database id)
      add-expanded-schedules
      (get-database-hydrate-include include)))


;;; ----------------------------------------- GET /api/database/:id/metadata -----------------------------------------

;; Since the normal `:id` param in the normal version of the endpoint will never match with negative numbers
;; we'll create another endpoint to specifically match the ID of the 'virtual' database. The `defendpoint` macro
;; requires either strings or vectors for the route so we'll have to use a vector and create a regex to only
;; match the virtual ID (and nothing else).
(api/defendpoint GET ["/:virtual-db/metadata" :virtual-db (re-pattern (str mbql.s/saved-questions-virtual-database-id))]
  "Endpoint that provides metadata for the Saved Questions 'virtual' database. Used for fooling the frontend
   and allowing it to treat the Saved Questions virtual DB just like any other database."
  []
  (saved-cards-virtual-db-metadata :include-tables? true, :include-fields? true))

(defn- db-metadata [id include-hidden?]
  (-> (api/read-check Database id)
      (hydrate [:tables [:fields [:target :has_field_values] :has_field_values] :segments :metrics])
      (update :tables (if include-hidden?
                        identity
                        (fn [tables]
                          (->> tables
                               (remove :visibility_type)
                               (map #(update % :fields filter-sensitive-fields))))))
      (update :tables (fn [tables]
                        (for [table tables
                              :when (mi/can-read? table)]
                          (-> table
                              (update :segments (partial filter mi/can-read?))
                              (update :metrics  (partial filter mi/can-read?))))))))

(api/defendpoint GET "/:id/metadata"
  "Get metadata about a `Database`, including all of its `Tables` and `Fields`.
   By default only non-hidden tables and fields are returned. Passing include_hidden=true includes them.
   Returns DB, fields, and field values."
  [id include_hidden]
  {include_hidden (s/maybe su/BooleanString)}
  (db-metadata id include_hidden))


;;; --------------------------------- GET /api/database/:id/autocomplete_suggestions ---------------------------------

(defn- autocomplete-tables [db-id prefix]
  (db/select [Table :id :db_id :schema :name]
    {:where    [:and [:= :db_id db-id]
                     [:= :active true]
                     [:like :%lower.name (str (str/lower-case prefix) "%")]
                     [:= :visibility_type nil]]
     :order-by [[:%lower.name :asc]]}))

(defn- autocomplete-fields [db-id prefix]
  (db/select [Field :name :base_type :semantic_type :id :table_id [:table.name :table_name]]
    :metabase_field.active          true
    :%lower.metabase_field.name     [:like (str (str/lower-case prefix) "%")]
    :metabase_field.visibility_type [:not-in ["sensitive" "retired"]]
    :table.db_id                    db-id
    {:order-by  [[:%lower.metabase_field.name :asc]
                 [:%lower.table.name :asc]]
     :left-join [[:metabase_table :table] [:= :table.id :metabase_field.table_id]]}))

(defn- autocomplete-results [tables fields]
  (concat (for [{table-name :name} tables]
            [table-name "Table"])
          (for [{:keys [table_name base_type semantic_type name]} fields]
            [name (str table_name
                       " "
                       base_type
                       (when semantic_type
                         (str " " semantic_type)))])))

(defn- autocomplete-suggestions [db-id prefix]
  (let [tables (filter mi/can-read? (autocomplete-tables db-id prefix))
        fields (readable-fields-only (autocomplete-fields db-id prefix))]
    (autocomplete-results tables fields)))

(api/defendpoint GET "/:id/autocomplete_suggestions"
  "Return a list of autocomplete suggestions for a given `prefix`.

  This is intened for use with the ACE Editor when the User is typing raw SQL. Suggestions include matching `Tables`
  and `Fields` in this `Database`.

  Tables are returned in the format `[table_name \"Table\"]`;
  Fields are returned in the format `[field_name \"table_name base_type semantic_type\"]`"
  [id prefix]
  {prefix su/NonBlankString}
  (api/read-check Database id)
  (try
    (autocomplete-suggestions id prefix)
    (catch Throwable t
      (log/warn "Error with autocomplete: " (.getMessage t)))))


;;; ------------------------------------------ GET /api/database/:id/fields ------------------------------------------

(api/defendpoint GET "/:id/fields"
  "Get a list of all `Fields` in `Database`."
  [id]
  (api/read-check Database id)
  (let [fields (filter mi/can-read? (-> (db/select [Field :id :display_name :table_id :base_type :semantic_type]
                                          :table_id        [:in (db/select-field :id Table, :db_id id)]
                                          :visibility_type [:not-in ["sensitive" "retired"]])
                                        (hydrate :table)))]
    (for [{:keys [id display_name table base_type semantic_type]} fields]
      {:id            id
       :name          display_name
       :base_type     base_type
       :semantic_type semantic_type
       :table_name    (:display_name table)
       :schema        (:schema table)})))


;;; ----------------------------------------- GET /api/database/:id/idfields -----------------------------------------

(api/defendpoint GET "/:id/idfields"
  "Get a list of all primary key `Fields` for `Database`."
  [id]
  (api/read-check Database id)
  (sort-by (comp str/lower-case :name :table) (filter mi/can-read? (-> (database/pk-fields {:id id})
                                                                       (hydrate :table)))))


;;; ----------------------------------------------- POST /api/database -----------------------------------------------

(defn- invalid-connection-response [field m]
  ;; work with the new {:field error-message} format but be backwards-compatible with the UI as it exists right now
  {:valid   false
   field    m
   :message m})

(defn test-database-connection
  "Try out the connection details for a database and useful error message if connection fails, returns `nil` if
   connection succeeds."
  [engine {:keys [host port] :as details}, & {:keys [invalid-response-handler]
                                              :or   {invalid-response-handler invalid-connection-response}}]
  {:pre [(some? engine)]}
  (let [engine  (keyword engine)
        details (assoc details :engine engine)]
    (try
      (cond
        (driver.u/can-connect-with-details? engine details :throw-exceptions)
        nil

        (and host port (u/host-port-up? host port))
        (invalid-response-handler :dbname (tru "Connection to ''{0}:{1}'' successful, but could not connect to DB."
                                               host port))

        (and host (u/host-up? host))
        (invalid-response-handler :port (tru "Connection to host ''{0}'' successful, but port {1} is invalid."
                                             host port))

        host
        (invalid-response-handler :host (tru "Host ''{0}'' is not reachable" host))

        :else
        (invalid-response-handler :db (tru "Unable to connect to database.")))
      (catch Throwable e
        (log/error e (trs "Cannot connect to Database"))
        (invalid-response-handler :dbname (.getMessage e))))))

;; TODO - Just make `:ssl` a `feature`
(defn- supports-ssl?
  "Does the given `engine` have an `:ssl` setting?"
  [driver]
  {:pre [(driver/available? driver)]}
  (let [driver-props (set (for [field (driver/connection-properties driver)]
                            (:name field)))]
    (contains? driver-props "ssl")))

(s/defn ^:private test-connection-details :- su/Map
  "Try a making a connection to database `engine` with `details`.

  If the `details` has SSL explicitly enabled, go with that and do not accept plaintext connections. If it is disabled,
  try twice: once with SSL, and a second time without if the first fails. If either attempt is successful, returns
  the details used to successfully connect. Otherwise returns a map with the connection error message. (This map will
  also contain the key `:valid` = `false`, which you can use to distinguish an error from valid details.)"
  [engine :- DBEngineString, details :- su/Map]
  (if (and (supports-ssl? (keyword engine))
           (true? (:ssl details)))
    (let [error (test-database-connection engine details)]
      (or error details))
    (let [details (if (supports-ssl? (keyword engine))
                    (assoc details :ssl true)
                    details)]
    ;; this loop tries connecting over ssl and non-ssl to establish a connection
    ;; if it succeeds it returns the `details` that worked, otherwise it returns an error
      (loop [details details]
        (let [error (test-database-connection engine details)]
          (if (and error
                   (true? (:ssl details)))
            (recur (assoc details :ssl false))
            (or error details)))))))

(api/defendpoint POST "/"
  "Add a new `Database`."
  [:as {{:keys [name engine details is_full_sync is_on_demand schedules auto_run_queries]} :body}]
  {name             su/NonBlankString
   engine           DBEngineString
   details          su/Map
   is_full_sync     (s/maybe s/Bool)
   is_on_demand     (s/maybe s/Bool)
   schedules        (s/maybe sync.schedules/ExpandedSchedulesMap)
   auto_run_queries (s/maybe s/Bool)}
  (api/check-superuser)
  (let [is-full-sync?    (or (nil? is_full_sync)
                             (boolean is_full_sync))
        details-or-error (test-connection-details engine details)
        valid?           (not= (:valid details-or-error) false)]
    (if valid?
      ;; no error, proceed with creation. If record is inserted successfuly, publish a `:database-create` event.
      ;; Throw a 500 if nothing is inserted
      (u/prog1 (api/check-500 (db/insert! Database
                                (merge
                                  {:name         name
                                   :engine       engine
                                   :details      details-or-error
                                   :is_full_sync is-full-sync?
                                   :is_on_demand (boolean is_on_demand)}
                                  (sync.schedules/schedule-map->cron-strings
                                    (if (:let-user-control-scheduling details)
                                      (sync.schedules/scheduling schedules)
                                      (sync.schedules/default-schedule)))
                                  (when (some? auto_run_queries)
                                    {:auto_run_queries auto_run_queries}))))
        (events/publish-event! :database-create <>))
      ;; failed to connect, return error
      {:status 400
       :body   details-or-error})))

(api/defendpoint POST "/validate"
  "Validate that we can connect to a database given a set of details."
  ;; TODO - why do we pass the DB in under the key `details`?
  [:as {{{:keys [engine details]} :details} :body}]
  {engine  DBEngineString
   details su/Map}
  (api/check-superuser)
  (let [details-or-error (test-connection-details engine details)]
    {:valid (not (false? (:valid details-or-error)))}))


;;; --------------------------------------- POST /api/database/sample_dataset ----------------------------------------

(api/defendpoint POST "/sample_dataset"
  "Add the sample dataset as a new `Database`."
  []
  (api/check-superuser)
  (sample-data/add-sample-dataset!)
  (Database :is_sample true))


;;; --------------------------------------------- PUT /api/database/:id ----------------------------------------------

(defn upsert-sensitive-fields
  "Replace any sensitive values not overriden in the PUT with the original values"
  [database details]
  (when details
    (merge (:details database)
           (reduce
            (fn [details k]
              (if (= protected-password (get details k))
                (m/update-existing details k (constantly (get-in database [:details k])))
                details))
            details
            (database/sensitive-fields-for-db database)))))

(api/defendpoint PUT "/:id"
  "Update a `Database`."
  [id :as {{:keys [name engine details is_full_sync is_on_demand description caveats points_of_interest schedules
                   auto_run_queries refingerprint]} :body}]
  {name               (s/maybe su/NonBlankString)
   engine             (s/maybe DBEngineString)
   refingerprint      (s/maybe s/Bool)
   details            (s/maybe su/Map)
   schedules          (s/maybe sync.schedules/ExpandedSchedulesMap)
   description        (s/maybe s/Str)                ; s/Str instead of su/NonBlankString because we don't care
   caveats            (s/maybe s/Str)                ; whether someone sets these to blank strings
   points_of_interest (s/maybe s/Str)
   auto_run_queries   (s/maybe s/Bool)}
  (api/check-superuser)
  ;; TODO - ensure that custom schedules and let-user-control-scheduling go in lockstep
  (api/let-404 [existing-database (Database id)]
    (let [details    (upsert-sensitive-fields existing-database details)
          conn-error (when (some? details)
                       (assert (some? engine))
                       (test-database-connection engine details))
          full-sync? (when-not (nil? is_full_sync)
                       (boolean is_full_sync))]
      (if conn-error
        ;; failed to connect, return error
        {:status 400
         :body   conn-error}
        ;; no error, proceed with update
        (do
          ;; TODO - is there really a reason to let someone change the engine on an existing database?
          ;;       that seems like the kind of thing that will almost never work in any practical way
          ;; TODO - this means one cannot unset the description. Does that matter?
          (api/check-500 (db/update-non-nil-keys! Database id
                                                  (merge
                                                     {:name               name
                                                      :engine             engine
                                                      :details            details
                                                      :refingerprint      refingerprint
                                                      :is_full_sync       full-sync?
                                                      :is_on_demand       (boolean is_on_demand)
                                                      :description        description
                                                      :caveats            caveats
                                                      :points_of_interest points_of_interest
                                                      :auto_run_queries   auto_run_queries}
                                                     (cond
                                                       ;; transition back to metabase managed schedules. the schedule
                                                       ;; details, even if provided, are ignored. database is the
                                                       ;; current stored value and check against the incoming details
                                                       (and (get-in existing-database [:details :let-user-control-scheduling])
                                                            (not (:let-user-control-scheduling details)))

                                                       (sync.schedules/schedule-map->cron-strings (sync.schedules/default-schedule))

                                                       ;; if user is controlling schedules
                                                       (:let-user-control-scheduling details)
                                                       (sync.schedules/schedule-map->cron-strings (sync.schedules/scheduling schedules))
                                                       ;; do nothing in the case that user is not in control of
                                                       ;; scheduling. leave them as they are in the db
                                                       ))))
          (let [db (Database id)]
            (events/publish-event! :database-update db)
            ;; return the DB with the expanded schedules back in place
            (add-expanded-schedules db)))))))


;;; -------------------------------------------- DELETE /api/database/:id --------------------------------------------

(api/defendpoint DELETE "/:id"
  "Delete a `Database`."
  [id]
  (api/let-404 [db (Database id)]
    (api/write-check db)
    (db/delete! Database :id id)
    (events/publish-event! :database-delete db))
  api/generic-204-no-content)


;;; --------------------------------------- GET /api/database/:id/sync_history ---------------------------------------

(api/defendpoint GET "/:id/sync_history"
  "Get the history of syncs performed (from task_history table) on this DB."
  [id]
  (api/check-superuser)
  (db/select TaskHistory :db_id id))

;;; ------------------------------------------ POST /api/database/:id/sync -------------------------------------------

;; TODO - Shouldn't we just check for superuser status instead of write checking?
;; NOTE Atte: This becomes maybe obsolete
(api/defendpoint POST "/:id/sync"
  "Update the metadata for this `Database`. This happens asynchronously."
  [id]
  ;; just publish a message and let someone else deal with the logistics
  ;; TODO - does this make any more sense having this extra level of indirection?
  ;; Why not just use a future?
  (events/publish-event! :database-trigger-sync (api/write-check Database id))
  {:status :ok})

;; NOTE Atte Keinänen: If you think that these endpoints could have more descriptive names, please change them.
;; Currently these match the titles of the admin UI buttons that call these endpoints

;; Should somehow trigger sync-database/sync-database!
(api/defendpoint POST "/:id/sync_schema"
  "Trigger a manual update of the schema metadata for this `Database`."
  [id]
  (api/check-superuser)
  ;; just wrap this in a future so it happens async
  (api/let-404 [db (Database id)]
    (future
      (sync-metadata/sync-db-metadata! db)
      (analyze/analyze-db! db)))
  {:status :ok})

;; TODO - do we also want an endpoint to manually trigger analysis. Or separate ones for classification/fingerprinting?

;; Should somehow trigger cached-values/cache-field-values-for-database!
(api/defendpoint POST "/:id/rescan_values"
  "Trigger a manual scan of the field values for this `Database`."
  [id]
  (api/check-superuser)
  ;; just wrap this is a future so it happens async
  (api/let-404 [db (Database id)]
    (future
      (sync-field-values/update-field-values! db)))
  {:status :ok})


;; "Discard saved field values" action in db UI
(defn- database->field-values-ids [database-or-id]
  (map :id (db/query {:select    [[:fv.id :id]]
                      :from      [[FieldValues :fv]]
                      :left-join [[Field :f] [:= :fv.field_id :f.id]
                                  [Table :t] [:= :f.table_id :t.id]]
                      :where     [:= :t.db_id (u/get-id database-or-id)]})))

(defn- delete-all-field-values-for-database! [database-or-id]
  (when-let [field-values-ids (seq (database->field-values-ids database-or-id))]
    (db/execute! {:delete-from FieldValues
                  :where       [:in :id field-values-ids]})))


;; TODO - should this be something like DELETE /api/database/:id/field_values instead?
(api/defendpoint POST "/:id/discard_values"
  "Discards all saved field values for this `Database`."
  [id]
  (api/check-superuser)
  (delete-all-field-values-for-database! id)
  {:status :ok})


;;; ------------------------------------------ GET /api/database/:id/schemas -----------------------------------------

(defn- can-read-schema?
  "Does the current user have permissions to know the schema with `schema-name` exists? (Do they have permissions to see
  at least some of its tables?)"
  [database-id schema-name]
  (perms/set-has-partial-permissions? @api/*current-user-permissions-set*
                                      (perms/object-path database-id schema-name)))

(api/defendpoint GET "/:id/schemas"
  "Returns a list of all the schemas found for the database `id`"
  [id]
  (api/read-check Database id)
  (->> (db/select-field :schema Table :db_id id, :active true, {:order-by [[:%lower.schema :asc]]})
       (filter (partial can-read-schema? id))
       ;; for `nil` schemas return the empty string
       (map #(if (nil? %) "" %))
       distinct
       sort))

(api/defendpoint GET ["/:virtual-db/schemas"
                      :virtual-db (re-pattern (str mbql.s/saved-questions-virtual-database-id))]
  "Returns a list of all the schemas found for the saved questions virtual database."
  []
  (when (public-settings/enable-nested-queries)
    (->> (cards-virtual-tables)
         (map :schema)
         distinct
         (sort-by str/lower-case))))


;;; ------------------------------------- GET /api/database/:id/schema/:schema ---------------------------------------

(defn- schema-tables-list [db-id schema]
  (api/read-check Database db-id)
  (api/check-403 (can-read-schema? db-id schema))
  (filter mi/can-read? (db/select Table
                         :db_id           db-id
                         :schema          schema
                         :active          true
                         :visibility_type nil
                         {:order-by [[:name :asc]]})))

(api/defendpoint GET "/:id/schema/:schema"
  "Returns a list of Tables for the given Database `id` and `schema`"
  [id schema]
  (api/check-404 (seq (schema-tables-list id schema))))

(api/defendpoint GET "/:id/schema/"
  "Return a list of Tables for a Database whose `schema` is `nil` or an empty string."
  [id]
  (api/check-404 (seq (concat (schema-tables-list id nil)
                              (schema-tables-list id "")))))

(api/defendpoint GET ["/:virtual-db/schema/:schema"
                      :virtual-db (re-pattern (str mbql.s/saved-questions-virtual-database-id))]
  "Returns a list of Tables for the saved questions virtual database."
  [schema]
  (when (public-settings/enable-nested-queries)
    (->> (source-query-cards
          :additional-constraints [(if (= schema (table-api/root-collection-schema-name))
                                      [:= :collection_id nil]
                                      [:in :collection_id (api/check-404 (seq (db/select-ids Collection :name schema)))])])
         (map table-api/card->virtual-table))))


(api/define-routes)
