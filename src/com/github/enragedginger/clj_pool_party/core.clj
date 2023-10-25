(ns com.github.enragedginger.clj-pool-party.core
  (:import (clojure.lang Ref)
           (java.util UUID)
           (java.util.concurrent.locks ReentrantLock)
           (java.util.concurrent Semaphore)))

(defn- gen-key []
  (str (UUID/randomUUID)))

(defn ^Ref build-pool
  "Builds and returns a clojure.lang.Ref representing an object pool
   Params:
   gen-fn - 0-arity function that produces an object when called
   max-size - The maximum number of objects to hold in the pool
   Optional params:
   health-check-fn - 1-arity function to call when performing health checks for objects in the pool
   close-fn - 1-arity function to call when closing / destroying an object in the pool"
  ([gen-fn max-size]
   (build-pool gen-fn max-size {}))
  ([gen-fn max-size {:keys [health-check-fn close-fn check-on-borrow? check-on-return?]}]
   (assert (pos? max-size) (str "max-size must be positive but was " max-size))
   (assert (or (nil? health-check-fn) (and health-check-fn (or check-on-borrow? check-on-return?)))
     (str "When supplying health-check-fn at least one of check-on-borrow? or check-on-return? must be true"))
   (ref
     {:gen-fn           gen-fn
      :health-check-fn  health-check-fn
      :check-on-borrow? check-on-borrow?
      :check-on-return? check-on-return?
      :close-fn         close-fn
      :writer-lock      (ReentrantLock.)
      :semaphore        (Semaphore. max-size)
      :objects          {}})))

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
  `(let [^Semaphore sem# (-> ~pool-ref deref :semaphore)]
     (try
       (.acquire sem#)
       ~@body
       (finally
         (.release sem#)))))

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
      (let [health-check-fn (:health-check-fn @pool-ref)
            check-on-borrow? (:check-on-borrow? @pool-ref)]
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
              (if (or (nil? health-check-fn) (not check-on-borrow?) (health-check-fn obj))
                [k obj]
                (do
                  (close-and-remove-entry pool-ref k)
                  (recur (rest available-objects)))))))))))

(defn- return-object
  "Returns the object at key 'k' back to the pool."
  [^Ref pool-ref k]
  (-with-pool-writer-lock pool-ref
    (let [obj (get-in @pool-ref [:objects k :obj])
          health-check-fn (:health-check-fn @pool-ref)
          check-on-return? (:check-on-return? @pool-ref)]
      (if (and health-check-fn check-on-return? (not (health-check-fn obj)))
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
