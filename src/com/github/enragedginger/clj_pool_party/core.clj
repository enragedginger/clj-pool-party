(ns com.github.enragedginger.clj-pool-party.core
  (:import (clojure.lang Ref)
           (java.util UUID)
           (java.util.concurrent.locks ReentrantLock)
           (java.util.concurrent Semaphore TimeUnit)))

(defn- gen-key []
  (str (UUID/randomUUID)))

(defn ^Ref build-pool
  "Builds and returns a clojure.lang.Ref representing an object pool
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
   (ref
     {:gen-fn                 gen-fn
      :borrow-health-check-fn borrow-health-check-fn
      :return-health-check-fn return-health-check-fn
      :close-fn               close-fn
      :wait-timeout-ms        (when wait-timeout-ms
                                (long wait-timeout-ms))
      :writer-lock            (ReentrantLock.)
      :semaphore              (Semaphore. max-size)
      :objects                {}})))

(defmacro -with-pool-writer-lock
  "Executes 'body' in the context of a pool's writer lock."
  [^Ref pool-ref & body]
  `(let [^ReentrantLock lock# (-> ~pool-ref deref :writer-lock)]
     (try
       (.lock lock#)
       ~@body
       (finally
         (.unlock lock#)))))

(defmacro -with-pool-semaphore
  "Executes 'body' in the context of a pool's reader semaphore.
   Potentially N of these can run in parallel where N is max-size."
  [^Ref pool-ref & body]
  `(let [^Semaphore sem# (-> ~pool-ref deref :semaphore)
         wait-timeout-ms# (-> ~pool-ref deref :wait-timeout-ms)]
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
  "Closes the object associated with the entry a key 'k' in the pool and removes the entry
  from the pool."
  [^Ref pool-ref k]
  (let [obj (get-in @pool-ref [:objects k :obj])]
    (close-obj-safe (:close-fn @pool-ref) obj)
    (dosync
      (ref-set pool-ref
        (update @pool-ref :objects (fn [objects]
                                     (dissoc objects k)))))))

(defn- borrow-object
  "Acquires an object from the pool. Re-uses an available object if present but will
  call gen-fn if no objects are available but space remains in the pool."
  [^Ref pool-ref]
  (-with-pool-writer-lock pool-ref
    (dosync
      (let [borrow-health-check-fn (:borrow-health-check-fn @pool-ref)]
        (loop [available-objects (->> @pool-ref :objects
                                   (filter #(-> % second :available?)))]
          (if (empty? available-objects)
            ;;if there's no objects available, create a new one
            (let [k (gen-key)
                  obj ((-> @pool-ref :gen-fn))]
              (ref-set pool-ref (assoc-in @pool-ref [:objects k] {:obj obj :available? false}))
              [k obj])
            (let [[k entry] (first available-objects)
                  obj (:obj entry)]
              ;;no health check or health check is positive, so return this object
              (if (or (nil? borrow-health-check-fn) (borrow-health-check-fn obj))
                [k obj]
                (do
                  (close-and-remove-entry pool-ref k)
                  (recur (rest available-objects)))))))))))

(defn- return-object
  "Returns the object at key 'k' back to the pool."
  [^Ref pool-ref k]
  (-with-pool-writer-lock pool-ref
    (let [obj (get-in @pool-ref [:objects k :obj])
          return-health-check-fn (:return-health-check-fn @pool-ref)]
      (if (and return-health-check-fn (not (return-health-check-fn obj)))
        (close-and-remove-entry pool-ref k)
        (dosync
          (ref-set pool-ref (assoc-in @pool-ref [:objects k :available?] true)))))))

(defn with-object
  "Borrows an object from the pool, applies it to f, and returns the result."
  [^Ref pool-ref f]
  (-with-pool-semaphore pool-ref
    (let [[k obj] (borrow-object pool-ref)]
      (try
        (f obj)
        (finally (return-object pool-ref k))))))

(comment
  (def id-atom
    (atom 0))
  (defn sample-gen-fn []
    (let [new-id (swap! id-atom inc)]
      {:id new-id}))
  (defn health-check-fn [x]
    (println "checking" x (-> x :id even?))
    (-> x :id even?))
  (def pool-ref (build-pool sample-gen-fn 5 {:health-check-fn  health-check-fn
                                             :check-on-return? true}))
  (with-object pool-ref
    (fn [obj]
      (println "borrowing obj:" obj)))
  )
