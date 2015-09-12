;;  Copyright (c) Eduardo Julian. All rights reserved.
;;  This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
;;  If a copy of the MPL was not distributed with this file,
;;  You can obtain one at http://mozilla.org/MPL/2.0/.

(ns lux.host
  (:require (clojure [string :as string]
                     [template :refer [do-template]])
            clojure.core.match
            clojure.core.match.array
            (lux [base :as & :refer [|do return* return fail fail* |let |case]]
                 [type :as &type]))
  (:import (java.lang.reflect Field Method Constructor Modifier)
           java.util.regex.Pattern))

;; [Constants]
(def prefix "lux.")
(def function-class (str prefix "Function"))
(def module-separator "/")
(def class-name-separator ".")
(def class-separator "/")

;; [Utils]
(defn ^:private class->type [^Class class]
  "(-> Class Type)"
  (if-let [[_ base arr-level] (re-find #"^([^\[]+)(\[\])*$"
                                       (str (if-let [pkg (.getPackage class)]
                                              (str (.getName pkg) ".")
                                              "")
                                            (.getSimpleName class)))]
    (if (.equals "void" base)
      &type/Unit
      (&type/Data$ (str (reduce str "" (repeat (int (/ (count arr-level) 2)) "["))
                        base)
                   (&/|list))
      )))

(defn ^:private method->type [^Method method]
  "(-> Method Type)"
  (class->type (.getReturnType method)))

;; [Resources]
(defn ^String ->class [class]
  (string/replace class (-> class-name-separator Pattern/quote re-pattern) class-separator))

(defn ^String ->class-name [module]
  (string/replace module (-> module-separator Pattern/quote re-pattern) class-name-separator))

(defn ^String ->module-class [module-name]
  (string/replace module-name (-> module-separator Pattern/quote re-pattern) class-separator))

(def ->package ->module-class)

(defn ->type-signature [class]
  ;; (assert (string? class))
  (case class
    "void"    "V"
    "boolean" "Z"
    "byte"    "B"
    "short"   "S"
    "int"     "I"
    "long"    "J"
    "float"   "F"
    "double"  "D"
    "char"    "C"
    ;; else
    (let [class* (->class class)]
      (if (.startsWith class* "[")
        class*
        (str "L" class* ";")))
    ))

(defn ->java-sig [^objects type]
  "(-> Type Text)"
  (|case type
    (&/$DataT ?name params)
    (->type-signature ?name)

    (&/$LambdaT _ _)
    (->type-signature function-class)

    (&/$TupleT (&/$Nil))
    "V"

    (&/$NamedT ?name ?type)
    (->java-sig ?type)

    _
    (assert false (str '->java-sig " " (&type/show-type type)))
    ))

(do-template [<name> <static?>]
  (defn <name> [class-loader target field]
    (if-let [type* (first (for [^Field =field (.getDeclaredFields (Class/forName (&type/as-obj target) true class-loader))
                                :when (and (.equals ^Object field (.getName =field))
                                           (.equals ^Object <static?> (Modifier/isStatic (.getModifiers =field))))]
                            (.getType =field)))]
      (return (class->type type*))
      (fail (str "[Host Error] Field does not exist: " target "." field))))

  lookup-static-field true
  lookup-field        false
  )

(do-template [<name> <static?>]
  (defn <name> [class-loader target method-name args]
    ;; (prn '<name> target method-name)
    (if-let [method (first (for [^Method =method (.getDeclaredMethods (Class/forName (&type/as-obj target) true class-loader))
                                 :when (and (.equals ^Object method-name (.getName =method))
                                            (.equals ^Object <static?> (Modifier/isStatic (.getModifiers =method)))
                                            (let [param-types (&/->list (seq (.getParameterTypes =method)))]
                                              (and (= (&/|length args) (&/|length param-types))
                                                   (&/fold2 #(and %1 (.equals ^Object %2 %3))
                                                            true
                                                            args
                                                            (&/|map #(.getName ^Class %) param-types)))))]
                             =method))]
      (return (method->type method))
      (fail (str "[Host Error] Method does not exist: " target "." method-name))))

  lookup-static-method  true
  lookup-virtual-method false
  )

(defn lookup-constructor [class-loader target args]
  (if-let [ctor (first (for [^Constructor =method (.getDeclaredConstructors (Class/forName (&type/as-obj target) true class-loader))
                             :when (let [param-types (&/->list (seq (.getParameterTypes =method)))]
                                     (and (= (&/|length args) (&/|length param-types))
                                          (&/fold2 #(and %1 (.equals ^Object %2 %3))
                                                   true
                                                   args
                                                   (&/|map #(.getName ^Class %) param-types))))]
                         =method))]
    (return &type/Unit)
    (fail (str "[Host Error] Constructor does not exist: " target))))

(defn location [scope]
  (->> scope (&/|map &/normalize-name) (&/|interpose "$") (&/fold str "")))
