(ns map-pool-party
  (:import (clojure.lang IFn)
           (java.util HashMap UUID)
           (java.util.concurrent.locks ReentrantLock)
           (java.util.concurrent Semaphore TimeUnit)))

(defn- gen-key []
  (UUID/randomUUID))

(deftype Pool [^IFn gen-fn ^Integer max-size ^IFn borrow-health-check-fn ^IFn return-health-check-fn
               ^IFn close-fn ^Long wait-timeout-ms
               ^ReentrantLock writer-lock ^Semaphore semaphore
               ^HashMap objects])

(deftype PoolEntry [obj available?])

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
     (HashMap.))))

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
  [^Pool pool k]
  (let [^HashMap objects-map (.objects pool)
        ^PoolEntry entry (.get objects-map k)]
    (close-obj-safe (.close-fn pool) (.obj entry))
    (.remove objects-map k)))

(defn- borrow-object
  "Acquires an object from the pool. Re-uses an available object if present but will
  call gen-fn if no objects are available but space remains in the pool."
  [^Pool pool]
  (-with-pool-writer-lock pool
    (dosync
      (let [borrow-health-check-fn (.borrow-health-check-fn pool)
            ^HashMap objects-map (.objects pool)]
        (loop [available-entries (->> objects-map
                                   (into {})
                                   (filter #(-> % second (.available?))))]
          (if (empty? available-entries)
            ;;if there's no objects available, create a new one
            (let [k (gen-key)
                  obj ((-> pool .gen-fn))]
              (.put objects-map k (PoolEntry. obj false))
              [k obj])
            (let [[k entry] (first available-entries)
                  obj (.obj entry)]
              ;;no health check or health check is positive, so return this object
              (if (or (nil? borrow-health-check-fn) (borrow-health-check-fn obj))
                (do
                  (.put objects-map k (PoolEntry. obj false))
                  [k obj])
                (do
                  (close-and-remove-entry pool k)
                  (recur (rest available-entries)))))))))))

(defn- return-object
  "Returns the object at key 'k' back to the pool."
  [^Pool pool k]
  (-with-pool-writer-lock pool
    (let [^HashMap objects-map (.objects pool)
          ^PoolEntry entry (.get objects-map k)
          return-health-check-fn (.return-health-check-fn pool)]
      (if (and return-health-check-fn (-> entry (.obj) return-health-check-fn not))
        (close-and-remove-entry pool k)
        (.put objects-map k (PoolEntry. (.obj entry) true))))))

(defn with-object
  "Borrows an object from the pool, applies it to f, and returns the result."
  [^Pool pool f]
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
    (doseq [k (->> (.objects pool)
                (into {})
                (keys))]
      (close-and-remove-entry pool k))
    (.release sem max-size)))
