# clj-pool-party

Simplistic object pooling library for Clojure. Has no dependencies other than Clojure itself.
Designed for multi-threaded environments. Supports virtual threads.

NOTE: The API for this library is currently in alpha and is subject to change.
Please provide any feedback you may have on the usefulness of this library.

## Rationale

I was recently upgrading an application to JDK21 and was experimenting with virtual threads.
A few of the libraries I was using for IO couldn't be used from virtual threads simply because
of how they were using object pooling. I was able to work around these issues by writing
my own object pool. In the process, I came to the conclusion that object pooling
is neither complicated to implement nor should it be complicated to use.

* Object pools should be thread-safe _and_ virtual thread-safe
* Object pools should be easy to use
* One should be able to define a basic object pool using nothing but a
generator function and a maximum size for the pool.
Everything else is optional. Implementing an entire interface or learning
the syntax of a fancy macro is needless complexity.


## Why didn't you just use these other libraries?
* [pool](https://github.com/kul/pool) - This library wraps the Apache commons object pool.
This is annoying in situations where you're trying to keep your dependency footprint light or
you end up needing a different, incompatible version of a transitive dependency for some reason.
The library hasn't seen any real activity since 2014. It wasn't clear at first glance if
it's thread safe / virtual thread safe and I'm not about to start searching Apache commons
object pool code to figure that out.
* [deepend](https://github.com/biiwide/deepend) - This library has some neat features,
but it's heavily dependent on [another library](https://github.com/clj-commons/dirigiste/tree/master).
The source code seems far more complicated that an object pool needs to be.
Also, it looks like `test.check` is marked as a runtime dependency instead of a dev / test dependency
which seems unnecessary. This library hasn't seen any activity since January 2019.

## Usage

TODO: add clojars link

```
(ns com.example
  (:require [com.github.enragedginger.clj-pool-party.core :as pool-party]))

(def id-atom
  (atom 0))

;;define a 0-arg generator function
;;clj-pool-party will call this function whenever it needs a new object for the pool
(defn sample-gen-fn []
  (let [new-id (swap! id-atom inc)]
    {:id new-id}))

;;define a health check function that takes an object from the pool as an argument
;;and returns a non-truthy value iff the object is considered unhealthy and should
;;be removed from the pool
(defn health-check-fn [x]
  (println "checking" x (-> x :id even?))
  (-> x :id even?))
  
;;construct a pool of max-size 5
(def pool-ref (pool-party/build-pool sample-gen-fn 5
                {:borrow-health-check-fn health-check-fn
                 :return-health-check-fn health-check-fn}))

;;Run a function in the context of an object from the pool
(pool-party/with-object pool-ref
  (fn [obj]
    (println "borrowing obj:" obj)))
```

`build-pool` and `with-object` are the important functions.
See their corresponding doc strings for more info.

## Remaining items:
* Add a function for shutting down the pool.

## Dev

clj-pool-party has a mixture of unit and property based tests. If you see a gap
in test coverage, feel free to contribute a PR with additional tests.
I've designed this library with JDK21 and virtual threads in mind;
you won't be able to run the tests without a JVM that has virtual threads enabled
(i.e. JDK 21 or JDK19 with preview enabled). However, the runtime itself
has no dependency on virtual threads so you should be able to run this on
Java 8 if you really want to do it.

## License

Copyright © 2023 Enraged Ginger LLC

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
