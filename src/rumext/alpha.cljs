;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2020 Andrey Antukh <niwi@niwi.nz>

(ns rumext.alpha
  (:refer-clojure :exclude [ref deref])
  (:require-macros [rumext.alpha :refer [defc fnc]])
  (:require
   ["react" :as react]
   ["react-dom" :as rdom]
   [goog.functions :as gf]
   [rumext.util :as util]))

(def create-element react/createElement)
(def Component react/Component)
(def Fragment react/Fragment)
(def Profiler react/Profiler)

;; --- Impl

(when (exists? js/Symbol)
  (extend-protocol IPrintWithWriter
    js/Symbol
    (-pr-writer [sym writer _]
      (-write writer (str "\"" (.toString sym) "\"")))))

(extend-type cljs.core.UUID
  INamed
  (-name [this] (str this))
  (-namespace [_] ""))

;; --- Main Api

(defn mount
  "Add element to the DOM tree. Idempotent. Subsequent mounts will
  just update element."
  [element node]
  (rdom/render element node)
  nil)

(defn unmount
  "Removes component from the DOM tree."
  [node]
  (rdom/unmountComponentAtNode node))

(defn portal
  "Render `element` in a DOM `node` that is ouside of current DOM hierarchy."
  [element node]
  (rdom/createPortal element node))

(defn create-ref
  []
  (react/createRef))

(defn ref-val
  "Given state and ref handle, returns React component."
  [ref]
  (unchecked-get ref "current"))

(defn set-ref-val!
  [ref val]
  (unchecked-set ref "current" val)
  val)

;; --- Raw Hooks

(defn useRef
  [initial]
  (react/useRef initial))

(defn useState
  [initial]
  (react/useState initial))

(defn useEffect
  [f deps]
  (react/useEffect f deps))

(defn useMemo
  [f deps]
  (react/useMemo f deps))

(defn useCallback
  [f deps]
  (react/useCallback f deps))

(defn useLayoutEffect
  [f deps]
  (react/useLayoutEffect f deps))

;; --- Hooks

(defn- adapt
  [o]
  (if (uuid? o)
    (str o)
    (if (instance? cljs.core/INamed o)
      (name o)
      o)))

;; "A convenience function that translates the list of arguments into a
;; valid js array for use in the deps list of hooks.

;; It translates INamed to Strings and uuid instances to strings for
;; correct equality check (react uses equivalent to `identical?` for
;; check the equality and uuid and INamed objects always returns false
;; to this check).

;; NOTE: identity is a hack for avoid a wrong number of args warning.

(def ^{:arglists '([& items])}
  deps
  (identity
   #(amap (js-arguments) i ret
          (adapt (aget ret i)))))

;; The cljs version of use-ref is identical to the raw (no
;; customizations/adaptations needed)
(def use-ref useRef)

(defn use-state
  [initial]
  (let [tmp (useState initial)
        state (aget tmp 0)
        set-state (aget tmp 1)]
    (reify
      cljs.core/IReset
      (-reset! [_ new-value]
        (set-state new-value))

      cljs.core/ISwap
      (-swap! [self f]
        (set-state f))
      (-swap! [self f x]
        (set-state #(f % x)))
      (-swap! [self f x y]
        (set-state #(f % x y)))
      (-swap! [self f x y more]
        (set-state #(apply f % x y more)))

     cljs.core/IDeref
     (-deref [self] state))))

(defn use-var
  "A custom hook for define mutable variables that persists
  on renders (based on useRef hook)."
  [initial]
  (let [ref (useRef initial)]
    (reify
      cljs.core/IReset
      (-reset! [_ new-value]
        (set-ref-val! ref new-value))

      cljs.core/ISwap
      (-swap! [self f]
        (set-ref-val! ref (f (ref-val ref))))
      (-swap! [self f x]
        (set-ref-val! ref (f (ref-val ref) x)))
      (-swap! [self f x y]
        (set-ref-val! ref (f (ref-val ref) x y)))
      (-swap! [self f x y more]
        (set-ref-val! ref (apply f (ref-val ref) x y more)))

      cljs.core/IDeref
      (-deref [self] (ref-val ref)))))

(defn use-effect
  ([f] (use-effect #js [] f))
  ([deps f]
   (useEffect #(let [r (^js f)] (if (fn? r) r identity)) deps)))

(defn use-layout-effect
  ([f] (use-layout-effect #js [] f))
  ([deps f]
   (useLayoutEffect #(let [r (^js f)] (if (fn? r) r identity)) deps)))

(defn use-memo
  ([f] (useMemo f #js []))
  ([deps f] (useMemo f deps)))

(defn use-callback
  ([f] (useCallback f #js []))
  ([deps f] (useCallback f deps)))

(defn deref
  [iref]
  (let [tmp (useState 0)
        state (aget tmp 0)
        set-state (aget tmp 1)
        key (useMemo
             #(let [key (js/Symbol "rumext.alpha/deref")]
                (add-watch iref key (fn [_ _ _ _] (^js set-state inc)))
                key)
             #js [iref])]

    (useEffect #(fn [] (remove-watch iref key))
               #js [iref key])
    (cljs.core/deref iref)))

;; --- Other API

(defn element
  ([klass]
   (create-element klass #js {}))
  ([klass props]
   (let [props (cond
                 (object? props) props
                 (map? props) (util/map->obj props)
                 :else (throw (ex-info "Unexpected props" {:props props})))]
     (create-element klass props))))

;; --- Higher-Order Components

(defn memo'
  "A raw variant of React.memo."
  [component equals?]
  (react/memo component equals?))

(defn memo
  ([component] (react/memo component))
  ([component eq?]
   (react/memo component #(util/props-equals? eq? %1 %2))))

(defn catch
  [component {:keys [fallback on-error]}]
  (let [constructor
        (fn [props]
          (this-as this
            (unchecked-set this "state" #js {})
            (.call Component this props)))

        did-catch
        (fn [error info]
          (when (fn? on-error)
            (on-error error info)))

        derive-state
        (fn [error]
          #js {:error error})

        render
        (fn []
          (this-as this
            (let [state (unchecked-get this "state")
                  props (unchecked-get this "props")
                  error (unchecked-get state "error")]
              (if error
                (react/createElement fallback #js {:error error})
                (react/createElement component props)))))

        _ (goog/inherits constructor Component)
        prototype (unchecked-get constructor "prototype")]

    (unchecked-set constructor "displayName" "ErrorBoundary")
    (unchecked-set constructor "getDerivedStateFromError" derive-state)
    (unchecked-set prototype "componentDidCatch" did-catch)
    (unchecked-set prototype "render" render)
    constructor))

(def ^:private schedule
  (or (and (exists? js/window) js/window.requestAnimationFrame)
      #(js/setTimeout % 16)))

(defn deferred
  ([component] (deferred component schedule))
  ([component sfn]
   (fnc deferred
     {::wrap-props false}
     [props]
     (let [tmp (useState false)
           ^boolean render? (aget tmp 0)
           ^js set-render (aget tmp 1)]
       (use-effect (fn [] (^js sfn #(set-render true))))
       (when render? (create-element component props))))))

(defn throttle
  [component ms]
  (fnc throttle
    {::wrap-props false}
    [props]
    (let [tmp (useState props)
          state (aget tmp 0)
          ^js set-state (aget tmp 1)
          set-state* (use-memo #(gf/throttle set-state ms))]
      (use-effect nil #(set-state* props))
      (create-element component state))))

