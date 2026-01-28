(ns hooks.fn-size
  "Custom clj-kondo hook to enforce function size limits.
   Functions exceeding 10 lines will trigger a warning."
  (:require [clj-kondo.hooks-api :as api]))

(def ^:private max-fn-lines 10)

(defn defn-hook
  "Hook for defn and defn- to check function body size.
   Warns if function body exceeds max-fn-lines."
  [{:keys [node]}]
  (let [children (:children node)
        ;; children: [defn-sym, fn-name, docstring?, attr-map?, args-or-arity, body...]
        ;; Skip defn symbol and name
        after-name (drop 2 children)
        ;; Skip docstring if present
        after-doc (if (api/string-node? (first after-name))
                    (rest after-name)
                    after-name)
        ;; Skip attr-map if present
        after-attr (if (api/map-node? (first after-doc))
                     (rest after-doc)
                     after-doc)
        ;; Check if multi-arity or single-arity
        first-form (first after-attr)
        single-arity? (api/vector-node? first-form)
        ;; For single-arity: [args body...], for multi-arity: ([args body...] ...)
        body-nodes (if single-arity?
                     (rest after-attr)
                     ;; Multi-arity: each child is a list (args body...)
                     ;; Check the largest arity body
                     (when (api/list-node? first-form)
                       (let [arities after-attr
                             largest-arity (apply max-key
                                                  (fn [arity]
                                                    (let [body (rest (:children arity))]
                                                      (if (seq body)
                                                        (- (or (:end-row (meta (last body))) 0)
                                                           (or (:row (meta (first body))) 0))
                                                        0)))
                                                  arities)]
                         (rest (:children largest-arity)))))
        fn-name (second children)]
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
  {:node node})
