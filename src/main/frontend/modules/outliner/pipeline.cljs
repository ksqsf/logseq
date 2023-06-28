(ns frontend.modules.outliner.pipeline
  (:require [clojure.set :as set]
            [datascript.core :as d]
            [electron.ipc :as ipc]
            [frontend.config :as config]
            [frontend.db :as db]
            [frontend.db.model :as db-model]
            [frontend.db.react :as react]
            [frontend.modules.datascript-report.core :as ds-report]
            [frontend.modules.outliner.file :as file]
            [frontend.state :as state]
            [frontend.util :as util]
            [logseq.db.schema :as db-schema]
            [promesa.core :as p]
            [cognitect.transit :as t]))

(defn updated-page-hook
  [tx-report page]
  (when (and
         (not (config/db-based-graph? (state/get-current-repo)))
         (not (get-in tx-report [:tx-meta :created-from-journal-template?])))
    (file/sync-to-file page (:outliner-op (:tx-meta tx-report)))))

;; TODO: it'll be great if we can calculate the :block/path-refs before any
;; outliner transaction, this way we can group together the real outliner tx
;; and the new path-refs changes, which makes both undo/redo and
;; react-query/refresh! easier.

;; TODO: also need to consider whiteboard transactions

;; Steps:
;; 1. For each changed block, new-refs = its page + :block/refs + parents :block/refs
;; 2. Its children' block/path-refs might need to be updated too.
(defn compute-block-path-refs
  [{:keys [tx-meta db-before]} blocks]
  (let [repo (state/get-current-repo)
        blocks (remove :block/name blocks)]
    (when (:outliner-op tx-meta)
      (when (react/path-refs-need-recalculated? tx-meta)
        (let [*computed-ids (atom #{})]
          (mapcat (fn [block]
                    (when (and (not (@*computed-ids (:block/uuid block))) ; not computed yet
                               (not (:block/name block)))
                      (let [parents (db-model/get-block-parents repo (:block/uuid block))
                            parents-refs (->> (mapcat :block/path-refs parents)
                                              (map :db/id))
                            old-refs (if db-before
                                       (set (map :db/id (:block/path-refs (d/entity db-before (:db/id block)))))
                                       #{})
                            new-refs (set (util/concat-without-nil
                                           [(:db/id (:block/page block))]
                                           (map :db/id (:block/refs block))
                                           parents-refs))
                            refs-changed? (not= old-refs new-refs)
                            children (db-model/get-block-children-ids repo (:block/uuid block))
                            ;; Builds map of children ids to their parent id and :block/refs ids
                            children-maps (into {}
                                                (map (fn [id]
                                                       (let [entity (db/entity [:block/uuid id])]
                                                         [(:db/id entity)
                                                          {:parent-id (get-in entity [:block/parent :db/id])
                                                           :block-ref-ids (map :db/id (:block/refs entity))}]))
                                                     children))
                            children-refs (map (fn [[id {:keys [block-ref-ids] :as child-map}]]
                                                 {:db/id id
                                                  ;; Recalculate :block/path-refs as db contains stale data for this attribute
                                                  :block/path-refs
                                                  (set/union
                                                   ;; Refs from top-level parent
                                                   new-refs
                                                   ;; Refs from current block
                                                   block-ref-ids
                                                   ;; Refs from parents in between top-level
                                                   ;; parent and current block
                                                   (loop [parent-refs #{}
                                                          parent-id (:parent-id child-map)]
                                                     (if-let [parent (children-maps parent-id)]
                                                       (recur (into parent-refs (:block-ref-ids parent))
                                                              (:parent-id parent))
                                                       ;; exits when top-level parent is reached
                                                       parent-refs)))})
                                               children-maps)]
                        (swap! *computed-ids set/union (set (cons (:block/uuid block) children)))
                        (util/concat-without-nil
                         [(when (and (seq new-refs)
                                     refs-changed?)
                            {:db/id (:db/id block)
                             :block/path-refs new-refs})]
                         children-refs))))
                  blocks))))))

(defn- filter-deleted-blocks
  [datoms]
  (keep
   (fn [d]
     (when (and (= :block/uuid (:a d)) (false? (:added d)))
       (:v d)))
   datoms))

(defn- datom->av-vector
  [db datom]
  (let [a (:a datom)
        v (:v datom)
        v' (cond
             (contains? db-schema/ref-type-attributes a)
             (when-some [block-uuid-datom (first (d/datoms db :eavt v :block/uuid))]
               [:block/uuid (str (:v block-uuid-datom))])

             (and (= :block/uuid a) (uuid? v))
             (str v)

             :else
             v)]
    (when v'
      [a v'])))

(defn invoke-hooks
  [tx-report]
  (let [tx-meta (:tx-meta tx-report)]
    (when (and (not (:from-disk? tx-meta))
               (not (:new-graph? tx-meta))
               (not (:replace? tx-meta)))
      (let [{:keys [pages blocks]} (ds-report/get-blocks-and-pages tx-report)
            repo (state/get-current-repo)
            refs-tx (util/profile
                     "Compute path refs: "
                     (set (compute-block-path-refs tx-report blocks)))
            truncate-refs-tx (map (fn [m] [:db/retract (:db/id m) :block/path-refs]) refs-tx)
            tx (util/concat-without-nil truncate-refs-tx refs-tx)
            tx-report' (if (seq tx)
                         (let [refs-tx-data' (:tx-data (db/transact! repo tx {:outliner/transact? true
                                                                              :replace? true}))]
                           ;; merge
                           (assoc tx-report :tx-data (concat (:tx-data tx-report) refs-tx-data')))
                         tx-report)
            importing? (:graph/importing @state/state)
            deleted-block-uuids (set (filter-deleted-blocks (:tx-data tx-report)))]

        (when-not importing?
          (react/refresh! repo tx-report'))

        (when (and (config/db-based-graph? repo) (not (:skip-persist? tx-meta)))
          (let [t-writer (t/writer :json)
                upsert-blocks (->> blocks
                                   (remove (fn [b] (contains? deleted-block-uuids (:block/uuid b))))
                                   (map (fn [b]
                                          (let [datoms (d/datoms (:db-after tx-report') :eavt (:db/id b))]
                                            (assoc b :datoms
                                                   (->> datoms
                                                        (keep
                                                         (partial datom->av-vector (:db-after tx-report')))
                                                        (t/write t-writer))))))
                                   (map (fn [b]
                                          (if-some [page-uuid (:block/uuid (d/entity (:db-after tx-report') (:db/id (:block/page b))))]
                                            (assoc b :page_uuid page-uuid)
                                            b)))
                                   (map (fn [b]
                                          (let [uuid (or (:block/uuid b) (random-uuid))]
                                            (assoc b :block/uuid uuid)))))]
            (p/let [_ipc-result (ipc/ipc :db-transact-data repo
                                         (pr-str
                                          {:blocks upsert-blocks
                                           :deleted-block-uuids deleted-block-uuids}))]
              ;; TODO: disable edit when transact failed to avoid future data-loss
              ;; (prn "DB transact result: " ipc-result)
              )))

        (when-not (:delete-files? tx-meta)
          (doseq [p (seq pages)]
            (updated-page-hook tx-report p)))

        (when (and state/lsp-enabled?
                   (seq blocks)
                   (not importing?)
                   (<= (count blocks) 1000))
          (state/pub-event! [:plugin/hook-db-tx
                             {:blocks  blocks
                              :deleted-block-uuids deleted-block-uuids
                              :tx-data (:tx-data tx-report)
                              :tx-meta (:tx-meta tx-report)}]))))))
