(ns ents.test-runner
  "Entry point for `jolt -M:test`. Requires each test namespace and runs
  clojure.test over them; exits non-zero on any failure."
  (:require [clojure.test :as t]
            [ents.core-test]))

(defn- exit [code]
  (cond
    (resolve 'jolt.host/exit) ((resolve 'jolt.host/exit) code)
    (resolve 'System/exit)    ((resolve 'System/exit) code)
    :else nil))

(defn -main [& _]
  (let [{:keys [fail error]} (t/run-tests 'ents.core-test)]
    (exit (if (zero? (+ fail error)) 0 1))))
