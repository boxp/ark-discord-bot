(ns hooks.fn-size
  "Custom clj-kondo hook to enforce function size limits.
   Functions exceeding 10 lines will trigger a warning."
  (:require [clj-kondo.hooks-api :as api]))

(def ^:private max-fn-lines 10)

(defn- count-lines
  "Count the number of lines in a node based on its metadata."
  [node]
  (let [{:keys [row end-row]} (meta node)]
    (if (and row end-row)
      (inc (- end-row row))
      0)))

(defn- find-fn-body
  "Extract the function body from defn children.
   Handles both (defn name [args] body) and (defn name docstring [args] body)."
  [children]
  (let [without-name (rest children)
        without-meta (if (and (seq without-name)
                              (= :map (:tag (first without-name))))
                       (rest without-name)
                       without-name)
        without-doc (if (and (seq without-meta)
                             (= :token (:tag (first without-meta)))
                             (string? (:value (first without-meta))))
                      (rest without-meta)
                      without-meta)
        without-args (if (and (seq without-doc)
                              (= :vector (:tag (first without-doc))))
                       (rest without-doc)
                       without-doc)]
    without-args))

(defn defn-hook
  "Hook for defn and defn- to check function body size.
   Warns if function body exceeds max-fn-lines."
  [{:keys [node]}]
  (let [children (:children node)
        fn-name (second children)
        body-nodes (find-fn-body children)]
    (when (seq body-nodes)
      (let [first-body (first body-nodes)
            last-body (last body-nodes)
            start-row (:row (meta first-body))
            end-row (:end-row (meta last-body))
            line-count (when (and start-row end-row)
                         (inc (- end-row start-row)))]
        (when (and line-count (> line-count max-fn-lines))
          (api/reg-finding!
           (assoc (meta fn-name)
                  :message (format "Function body exceeds %d lines (%d lines). Consider refactoring."
                                   max-fn-lines line-count)
                  :type :fn-size-limit))))))
  ;; Return unchanged node
  {:node node})
