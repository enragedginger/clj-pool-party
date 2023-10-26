(ns array-deque-pool-party
  (:import (clojure.lang IFn)
           (java.util ArrayDeque)
           (java.util.concurrent.locks ReentrantLock)
           (java.util.concurrent Semaphore TimeUnit)))

(deftype Pool [^IFn gen-fn ^Integer max-size ^IFn borrow-health-check-fn ^IFn return-health-check-fn
               ^IFn close-fn ^Long wait-timeout-ms
               ^ReentrantLock writer-lock ^Semaphore semaphore
               ^"[Ljava.lang.Object;" objects-array
               ^ArrayDeque available-occupied-indices
               ^ArrayDeque available-unoccupied-indices])

(defn ^Pool build-pool
  "Builds and returns an object pool
   Params:
   gen-fn - 0-arity function that produces an object for pooling when called.
   max-size - The maximum number of objects to hold in the pool
   Optional params:
   borrow-health-check-fn - Function which takes an instance of an object from the pool that is about to
                            be borrowed and returns a truthy value iff it's suitable for use. Otherwise,
                            the object is removed from the pool. Note this is only called when the pool
                            attempts to provide an object that has already been used; it is not called
                            when the pool generates a new object using gen-fn. gen-fn should always
                            generate a 'healthy' object. How you fulfill that guarantee or handle situations
                            where gen-fn fails to fulfill that guarantee is up to you.
   return-health-check-fn - Function which takes an instance of an object that is about to be returned
                            to the pool and returns a truthy value iff it's suitable for use. Otherwise,
                            the object is removed from the pool.
   close-fn - 1-arity function to call when closing / destroying an object in the pool
   wait-timeout-ms - The number of milliseconds to wait if the pool is at max capacity. An exception
                     is thrown if this is exceeded."
  ([gen-fn max-size]
   (build-pool gen-fn max-size {}))
  ([gen-fn max-size {:keys [close-fn borrow-health-check-fn return-health-check-fn wait-timeout-ms]}]
   (assert (pos? max-size) (str "max-size must be positive but was " max-size))
   (assert (or (nil? wait-timeout-ms) (pos? (long wait-timeout-ms)))
     (str "wait-timeout-ms must be positive but was " wait-timeout-ms))
   (Pool. gen-fn max-size borrow-health-check-fn return-health-check-fn close-fn
     (when wait-timeout-ms
       (long wait-timeout-ms))
     (ReentrantLock.)
     (Semaphore. max-size)
     (make-array Object max-size)
     (ArrayDeque.)
     (ArrayDeque. (range max-size)))))

(defmacro -with-pool-writer-lock
  "Executes 'body' in the context of a pool's writer lock."
  [^Pool pool & body]
  `(let [^ReentrantLock lock# (.writer-lock ~pool)]
     (try
       (.lock lock#)
       ~@body
       (finally
         (.unlock lock#)))))

(defmacro -with-pool-semaphore
  "Executes 'body' in the context of a pool's reader semaphore.
   Potentially N of these can run in parallel where N is max-size."
  [^Pool pool & body]
  `(let [^Semaphore sem# (.semaphore ~pool)
         wait-timeout-ms# (.wait-timeout-ms ~pool)]
     (if (nil? wait-timeout-ms#)
       (try
         (.acquire sem#)
         ~@body
         (finally
           (.release sem#)))
       (if (.tryAcquire sem# 1 wait-timeout-ms# TimeUnit/MILLISECONDS)
         (try
           ~@body
           (finally
             (.release sem#)))
         (throw (ex-info (str "Could not acquire pool reader lock in " wait-timeout-ms# " ms")
                  {:wait-timeout-ms wait-timeout-ms#}))))))

(defn- close-obj-safe
  "Applies close-fn to obj. Catches and prints any errors. Does nothing if close-fn is nil."
  [close-fn obj]
  (when close-fn
    (try
      (close-fn obj)
      (catch Exception e
        (println "Error while closing entry in pool party:" e)))))

(defn- close-and-remove-entry
  "Closes the object associated with the entry at key 'k' in the pool and removes the entry
  from the pool."
  [^Pool pool ^Integer idx]
  (let [^"[Ljava.lang.Object;" objects-array (.objects-array pool)
        ^Object obj (aget objects-array idx)
        ^ArrayDeque available-unoccupied-indices (.available-unoccupied-indices pool)]
    (when obj
      (close-obj-safe (.close-fn pool) obj)
      (aset objects-array idx nil)
      (.remove (.available-occupied-indices pool) idx)
      (.push available-unoccupied-indices idx))))

(defn- borrow-object
  "Acquires an object from the pool. Re-uses an available object if present but will
  call gen-fn if no objects are available but space remains in the pool."
  [^Pool pool]
  (-with-pool-writer-lock pool
    (let [borrow-health-check-fn (.borrow-health-check-fn pool)
          ^"[Ljava.lang.Object;" objects-array (.objects-array pool)
          ^ArrayDeque available-occupied-indices (.available-occupied-indices pool)
          ^ArrayDeque available-unoccupied-indices (.available-unoccupied-indices pool)
          pre-existing-entry (loop []
                               (when-let [idx (.poll available-occupied-indices)]
                                 (let [obj (aget objects-array idx)]
                                   ;;no health check or health check is positive, so return this object
                                   (if (or (nil? borrow-health-check-fn) (borrow-health-check-fn obj))
                                     [idx obj]
                                     (do
                                       (close-and-remove-entry pool idx)
                                       (recur))))))
          entry (if pre-existing-entry
                  pre-existing-entry
                  (if-let [idx (.poll available-unoccupied-indices)]
                    ;;if there's no objects available, create a new one
                    (let [obj ((.gen-fn pool))]
                      (aset objects-array idx obj)
                      [idx obj])))]
      (if entry
        entry
        (throw (ex-info "Entire pool is in use, but writer lock was granted. This shouldn't happen"
                 {:pool pool})))
      )))

(defn- return-object
  "Returns the object at key 'k' back to the pool."
  [^Pool pool ^Integer idx]
  (-with-pool-writer-lock pool
    (let [^"[Ljava.lang.Object;" objects-array (.objects-array pool)
          ^ArrayDeque available-occupied-indices (.available-occupied-indices pool)
          obj (aget objects-array idx)
          return-health-check-fn (.return-health-check-fn pool)]
      (if (and return-health-check-fn (-> obj return-health-check-fn not))
        (close-and-remove-entry pool idx)
        (.push available-occupied-indices idx)))))

(defn with-object
  "Borrows an object from the pool, applies it to f, and returns the result."
  [^Pool pool ^IFn f]
  (-with-pool-semaphore pool
    (let [[k obj] (borrow-object pool)]
      (try
        (f obj)
        (finally (return-object pool k))))))

(defn evict-all
  "Acquires all locks and then closes and evicts all objects from the pool."
  [^Pool pool]
  (let [^Semaphore sem (.semaphore pool)
        max-size (.max-size pool)]
    (dotimes [idx max-size]
      (.acquire sem))
    (dotimes [idx max-size]
      (close-and-remove-entry pool idx))
    (.release sem max-size)))
