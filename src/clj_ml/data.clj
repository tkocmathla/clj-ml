;;
;; Manipulation of datasets and instances
;; @author Antonio Garrote
;;

(ns #^{:author "Antonio Garrote <antoniogarrote@gmail.com>"}
  clj-ml.data
  "This namespace contains several functions for
   building creating and manipulating data sets and instances. The formats of
   these data sets as well as their classes can be modified and assigned to
   the instances. Finally data sets can be transformed into Clojure sequences
   that can be transformed using usual Clojure functions like map, reduce, etc."
  (:use [clj-ml utils])
  (:use [clj-ml.io :only [load-instances save-instances]])
  (:require [clj-ml.filters :as filters])
  (:require [clojure.string :as str])
  (:require [clojure.set :as set])
  (:use [clojure.java.io :only [file]])
  (:import (weka.core Instance DenseInstance SparseInstance Instances FastVector Attribute)
           (cljml ClojureInstances)))

(declare dataset-seq)
(declare instance-index-attr dataset-index-attr)

;; Common functions

(defn enumeration-or-nil-seq [s]
  "Returns the result of enumeration-seq if the input sequence is not nil,
  an empty list otherwise"
  (if (nil? s) '() (enumeration-seq s)))

(defn is-instance?
  "Checks if the provided object is an instance"
  [instance]
  (instance? weka.core.Instance instance))

(defn is-dataset?
  "Checks if the provided object is a dataset"
  [dataset]
  (instance? weka.core.Instances dataset))


(defn instance-attribute-at [^Instance instance index-or-name]
  (.attribute instance (int (instance-index-attr instance index-or-name))))

(defn dataset-attribute-at [^Instances dataset index-or-name]
  (.attribute dataset (int (dataset-index-attr dataset index-or-name))))

(defn copy-dataset
  "Uses the Instances constructor to copy a given dataset.  Each Instance (row) will be shallow copied. So, while
   not all the data is copied you will be creating n new Instance objects, where n is the number of training examples."
  [^Instances ds]
  (Instances. ds))

(defn attribute-at
  "Returns attribute situated at the provided position or the provided name."
  [dataset-or-instance index-or-name]
  (if (is-instance? dataset-or-instance)
    (instance-attribute-at dataset-or-instance index-or-name)
    (dataset-attribute-at dataset-or-instance index-or-name)))

(defn attribute-name-at
  "Returns the name of an attribute situated at the provided position in
   the attributes definition of an instance or class"
  [dataset-or-instance index-or-name]
  (let [^Attribute class-attr (attribute-at dataset-or-instance index-or-name)]
    (.name class-attr)))

(defn dataset-attributes
  "Returns the attributes (weka.core.Attribute) of the dataset or instance"
  [^Instances dataset]
  (map #(.attribute dataset (int %)) (range (.numAttributes dataset))))

(defn instance-attributes
  "Returns the attributes (weka.core.Attribute) of the dataset or instance"
  [^Instance instance]
  (map #(.attribute instance (int %)) (range (.numAttributes instance))))

(defn attributes
  "Returns the attributes (weka.core.Attribute) of the dataset or instance"
  [dataset-or-instance]
  (if (is-instance? dataset-or-instance)
    (instance-attributes dataset-or-instance)
    (dataset-attributes dataset-or-instance)))

(defn attr-name [^Attribute attr]
  (.name attr))

(defn keyword-name [attr]
  (keyword (attr-name attr)))

(defn attribute-names
  "Returns the attribute names, as keywords, of the dataset or instance"
  [dataset-or-instance]
  (map keyword-name (attributes dataset-or-instance)))

(defn numeric-attributes
  "Returns the numeric attributes (weka.core.Attribute) of the dataset or instance"
  [dataset-or-instance]
  (filter #(.isNumeric ^Attribute %) (attributes dataset-or-instance)))

(defn nominal-attributes
  "Returns the string attributes (weka.core.Attribute) of the dataset or instance"
  [dataset-or-instance]
  (filter #(.isNominal ^Attribute %) (attributes dataset-or-instance)))

(defn string-attributes
  "Returns the string attributes (weka.core.Attribute) of the dataset or instance"
  [dataset-or-instance]
  (filter #(.isString ^Attribute %) (attributes dataset-or-instance)))

(defn nominal-attribute
  "Creates a nominal weka.core.Attribute with the given name and labels"
  [attr-name labels]
  (Attribute. ^String (name attr-name) ^FastVector (into-fast-vector (map name labels))))

(defn dataset-index-attr
  "Returns the index of an attribute in the attributes definition of a dataset."
  [^Instances dataset attr]
  (if (number? attr)
    attr
    (find-first #(= (name attr) (attr-name (.attribute dataset (int %)))) (range (.numAttributes dataset)))))

(defn instance-index-attr
  "Returns the index of an attribute in the attributes definition of an
   instance or dataset"
  [^Instance instance attr]
  (if (number? attr)
    attr
    (find-first #(= (name attr) (attr-name (.attribute instance (int %)))) (range (.numAttributes instance)))))

;; Construction of individual data and datasets

(defn- double-or-nan [x]
  (if (nil? x) Double/NaN (double x)))

(defn make-instance
  "Creates a new dataset instance from a vector"
  ([dataset vector]
   (make-instance dataset 1 vector))
  ([dataset weight vector]
   (let [^Instance inst (DenseInstance. (count vector))]
       (do (.setDataset inst dataset)
           (loop [vs vector
                  c 0]
             (if (empty? vs)
               (doto inst (.setWeight (double weight)))
               (do
                 (if (or (keyword? (first vs)) (string? (first vs)))
                   ;; this is a nominal entry in keyword or string form
                   (.setValue inst (int c) (name (first vs)))
                   (if (sequential? (first vs))
                     ;; this is a map of labels
                     (let [k (name (nth (first vs) 0))
                           val (nth (first vs) 1)
                           ik  (int (instance-index-attr inst k))]
                       (if (or (keyword? val) (string? val))
                         ;; this is a nominal entry in keyword or string form
                         (.setValue inst ik ^String (name val))
                         (.setValue inst ik (double-or-nan val))))
                     ;; A double value for the entry
                     (.setValue inst (int c) (double-or-nan (first vs)))))
                 (recur (rest vs)
                        (+ c 1)))))))))

(defn make-sparse-instance
  "Creates a new dataset instance from a map of index-value pairs (as
  a clojure map), where index starts at 0. Use explicit Double/NaN for
  missing values; all other values are assumed to be zeros."
  ([dataset valmap]
   (make-sparse-instance dataset 1 valmap))
  ([^Instances dataset weight valmap]
   (let [^SparseInstance inst (SparseInstance. (.numAttributes dataset))]
     (do (.setDataset inst dataset)
         (loop [idxs (range (.numAttributes dataset))]
           (if (empty? idxs)
             (doto inst (.setWeight (double weight)))
             (do
               (let [idx (first idxs)
                     val (get valmap idx)]
                 (if (nil? val)
                   (.setValue inst idx 0.0)
                   (if (or (keyword? val) (string? val))
                     ;; this is a nominal entry in keyword or string form
                     (.setValue inst idx (name val))
                     (if (sequential? val)
                       ;; this is a map of labels
                       (let [k (name (nth val 0))
                             val2 (nth val 1)
                             ik  (int (instance-index-attr inst k))]
                         (if (or (keyword? val2) (string? val2))
                           ;; this is a nominal entry in keyword or string form
                           (.setValue inst ik ^String (name val2))
                           (.setValue inst ik val2)))
                       ;; A double value for the entry
                       (.setValue inst idx (double val))))))
               (recur (rest idxs)))))))))

(defn- parse-attributes
  "Builds a set of attributes for a dataset parsed from the given array"
  ([attributes]
     (loop [atts attributes
            fv (new FastVector (count attributes))]
       (if (empty? atts)
         fv
         (do
           (let [att (first atts)]
             (.addElement fv
                          (if (map? att)
                            (if (sequential? (first (vals att)))
                              (let [v (first (vals att))
                                    vfa (reduce (fn [a i] (.addElement a (name i)) a)
                                                (new FastVector) v)]
                                (new Attribute (name (first (keys att))) vfa))
                              (let [^weka.core.FastVector tmp (first (vals att))]
                                (new Attribute (name (first (keys att))) tmp)))
                            (new Attribute (name att)))))
           (recur (rest atts)
                  fv))))))

(defn make-dataset
  "Creates a new dataset, empty or with the provided instances and options"
  ([ds-name attributes capacity-or-labels & opts]
     (let [options (first-or-default opts {})
           weight (get options :weight 1)
           class-attribute (get options :class)
           sparse? (get options :sparse)
           ds (if (sequential? capacity-or-labels)
                ;; we have received a sequence instead of a number, so we initialize data
                ;; instances in the dataset
                (let [dataset (new ClojureInstances (name ds-name) (parse-attributes attributes) (count capacity-or-labels))]
                  (loop [vs capacity-or-labels]
                    (if (empty? vs)
                      dataset
                      (do
                        (let [inst (if sparse?
                                     (make-sparse-instance dataset weight (first vs))
                                     (make-instance dataset weight (first vs)))]
                          (.add dataset inst))
                        (recur (rest vs))))))
                ;; we haven't received a vector so we create an empty dataset
                (new Instances (name ds-name) (parse-attributes attributes) capacity-or-labels))]
       ;; we try to setup the class attribute if :class with a attribute name or
       ;; integer value is provided
       (when (not (nil? class-attribute))
         (let [index-class-attribute (if (keyword? class-attribute)
                                       (loop [c 0
                                              acum attributes]
                                         (if (= (let [at (first acum)]
                                                  (if (map? at)
                                                    (first (keys at))
                                                    at))
                                                class-attribute)
                                           c
                                           (if (= c (count attributes))
                                             (throw (new Exception "provided class attribute not found"))
                                             (recur (+ c 1)
                                                    (rest acum)))))
                                       class-attribute)]
           (.setClassIndex ds index-class-attribute)))
       ds)))

(defn make-sparse-dataset
  "Creates a new dataset, empty or with the provided instances and options"
  [ds-name attributes capacity-or-labels & opts]
  (apply make-dataset ds-name attributes capacity-or-labels [(merge (first-or-default opts {})
                                                                    {:sparse true})]))

;; dataset information

(defn dataset-name
  "Returns the name of this dataset"
  [^Instances dataset]
  (.relationName dataset))

(defn dataset-set-name
  "Sets the dataset's name"
  [^Instances dataset ^String new-name]
  (doto dataset (.setRelationName new-name)))

(defn dataset-append-name
  "Sets the dataset's name"
  [^Instances dataset ^String name-addition]
  (doto dataset (.setRelationName ^String (str (.relationName dataset) name-addition))))

(defn attribute-labels-indexes
  "Returns map of the labels (possible values) for the given nominal attribute as the keys
   with the values being the attributes index. "
  [^Attribute attr]
  (let [values (enumeration-or-nil-seq (.enumerateValues attr))]
    (if (empty? values)
      {}
      (reduce (fn [m ^String val]
                (assoc m (keyword val) (.indexOfValue attr val)))
              {}
              values))))

(defn attribute-labels
  "Returns the labels (possible values) for the given nominal attribute as keywords."
  [^Attribute attr]
  (set (map keyword (enumeration-or-nil-seq (.enumerateValues attr)))))

(defn attribute-labels-as-strings
  "Returns the labels (possible values) for the given nominal attribute as strings."
  [^Attribute attr]
  (set (enumeration-or-nil-seq (.enumerateValues attr))))

(defn dataset-labels-at [dataset-or-instance index-or-name]
  "Returns the lables (possible values) for a nominal attribute at the provided position"
  (attribute-labels-indexes
   (attribute-at dataset-or-instance index-or-name)))

(defn dataset-class-labels
  "Returns the possible labels for the class attribute"
  [^Instances dataset]
  (dataset-labels-at dataset (.classIndex dataset)))

(defn dataset-format
  "Returns the definition of the attributes of this dataset"
  [dataset]
  (reduce
   (fn [so-far ^Attribute attr]
     (conj so-far
           (if (.isNominal attr)
             {(keyword-name attr) (map keyword (enumeration-or-nil-seq (.enumerateValues attr)))}
             (keyword-name attr))))
   []
   (attributes dataset)))

(defn headers-only
  "Returns a new weka dataset (Instances) with the same headers as the given one"
  [^Instances ds]
  (Instances. ds 0))

(defn dataset-class-index
  "Returns the index of the class attribute for this dataset"
  [^Instances dataset]
  (.classIndex dataset))

(defn dataset-class-name
  "Returns the name of the class attribute in keyword form.  Returns nil if not set."
  [^Instances dataset]
  (when (> (dataset-class-index dataset) -1)
    (keyword-name (.classAttribute dataset))))

(defn dataset-nominal?
  "Returns boolean indicating if the class attribute is nominal"
  [^Instances dataset]
  (.. dataset classAttribute isNominal))

(defn dataset-class-values
  "Returns a lazy-seq of the values for the dataset's class attribute.
If the class is nominal then the string value (not keyword) is returned."
  [^Instances dataset]
  (let [class-attr (.classAttribute dataset)
        class-value (if (.isNominal class-attr)
                      (fn [^Instance i] (.stringValue i class-attr))
                      (fn [^Instance i] (.classValue i)))] ;classValue returns the double
    (map class-value (dataset-seq dataset))))

(defn dataset-weights
  "Returns a lazy-seq of the weights of the dataset instances."
  [^Instances dataset]
  (map (fn [^Instance i] (.weight i)) (dataset-seq dataset)))

;; manipulation of instances

(defn instance-set-class
  "Sets the value (label) of the class attribute for this instance"
  [^Instance instance val]
  (doto instance (.setClassValue val)))

(defn instance-set-class-missing
  "Sets the class to \"missing\""
  [^Instance instance]
  (doto instance (.setClassMissing)))

(defn instance-get-class
  "Get the class attribute for this instance; returns nil if the class is \"missing\""
  [^Instance instance]
  (when (not (Double/isNaN (.classValue instance)))
    (keyword (.value (.classAttribute instance) (.classValue instance)))))

(defn instance-value-at
  "Returns the value of an instance attribute. A string, not a keyword is returned."
  [^Instance instance pos]
  (let [pos (int pos)
        attr (.attribute instance pos)
        val (.value instance pos)]
    ; This ignores the fact that weka can have date and other attribute types...
    (cond (Double/isNaN val) nil
          (.isNominal attr) (keyword (.stringValue instance pos))
          (.isString attr) (.stringValue instance pos)
          :else val)))

(defn instance-to-list
  "Builds a list with the values of the instance"
  [^Instance instance]
  (map (partial instance-value-at instance) (range (.numAttributes instance))))

(defn instance-to-vector
  "Builds a vector with the values of the instance"
  [instance]
  (vec (instance-to-list instance)))

(defn instance-to-map
  "Builds a vector with the values of the instance"
  [^Instance instance]
  (with-meta (reduce (fn [m i]
                       (assoc m (keyword (attribute-name-at instance i)) (instance-value-at instance i)))
                     {}
                     (range (.numAttributes instance)))
    {:weight (.weight instance)}))


;; manipulation of datasets

(defn dataset-seq
  "Builds a new clojure sequence from this dataset"
  [dataset]
  (if (= (class dataset)
         ClojureInstances)
    (seq dataset)
    (seq (enumeration-or-nil-seq (.enumerateInstances ^Instances dataset)))))

(defn dataset-as-maps
  "Returns a lazy sequence of the dataset represetned as maps.
This fn is preferale to mapping over a seq yourself with instance-to-map
becuase it avoids redundant string interning of the attribute names."
  [dataset]
  (let [attrs (attribute-names dataset)] ; we only want to intern the attribute names once!
    (for [^Instance instance (dataset-seq dataset)]
      (with-meta
        (zipmap attrs (instance-to-list instance))
        {:weight (.weight instance)}))))

(defn dataset-as-lists
  "Returns a lazy sequence of the dataset represented as lists.  The values
   are the actual values (i.e. the string values) and not weka's internal
   double representation or clj-ml's keyword representation."
  [dataset]
  (map instance-to-list (dataset-seq dataset)))

(defn dataset-as-vecs
  "Returns a lazy sequence of the dataset represented as lists.  The values
   are the actual values (i.e. the string values) and not weka's internal
   double representation or clj-ml's keyword representation."
  [dataset]
  (map instance-to-vector (dataset-seq dataset)))

(defn dataset-set-class
  "Sets the index of the attribute of the dataset that is the class of the dataset"
  [^Instances dataset index-or-name]
  (doto dataset (.setClassIndex ^int (dataset-index-attr dataset index-or-name))))

(defn dataset-remove-attribute-at
  "Removes the attribute at the specified index"
  [^Instances dataset index]
  (doto dataset (.deleteAttributeAt index)))

(defn dataset-remove-class
  "Removes the class attribute from the dataset"
  [^Instances dataset]
  (let [cidx (.classIndex dataset)]
    (if (= -1 cidx) dataset (dataset-remove-attribute-at dataset cidx))))

(defn dataset-count
  "Returns the number of elements in a dataset"
  [^Instances dataset]
  (.numInstances dataset))

(defn dataset-add
  "Adds a new instance to a dataset. A clojure vector, map, or an Instance
   can be passed as arguments"
  ([dataset vector]
     (dataset-add dataset 1 vector))
  ([^Instances dataset weight vector]
     (doto dataset
       (.add ^Instance (if (is-instance? vector)
                         vector
                         (make-instance dataset weight vector))))))

(defn dataset-extract-at
  "Removes and returns the instance at a certain position from the dataset"
  [^Instances dataset pos]
  (let [inst (.instance dataset (int pos))]
    (do
      (.delete dataset (int pos))
      inst)))

(defn dataset-at
  "Returns the instance at a certain position from the dataset"
  [^Instances dataset pos]
  (.instance dataset (int pos)))

(defn dataset-pop
  "Removes and returns the first instance in the dataset"
  [dataset]
  (dataset-extract-at dataset 0))

(defn dataset-replace-attribute!
  "Replaces the specified attribute with the given one. (The attribute should be a weka.core.Attribute)
This function only modifies the format of the dataset and does not deal with any instances.
The intention is for this to be used on data-formats and not on datasets with data."
  [^Instances dataset attr-name ^Attribute new-attr]
  (let [attr-pos (dataset-index-attr dataset attr-name)]
    (doto dataset
      (.deleteAttributeAt (int attr-pos))
      (.insertAttributeAt new-attr (int attr-pos)))))

(defn randomize-dataset!
  "Randomizes the dataset in place and returns the dataset.
   When no seed is provided then a randmon seed is created."
  ([ds]
     (randomize-dataset! ds (java.util.Random.)))
  ([^Instances ds seed]
     (let [seed (if (number? seed) (java.util.Random. seed) seed)]
       (doto ds (.randomize seed) (dataset-append-name (str "-Randomized("
                                                            (.hashCode ^Object seed)
                                                            ")"))))))
(defn attribute-value-fn
  "Takes a dataset and an attribute name, returns a function that will select the attribute value
   of a given instance from the dataset."
  [ds attr-name]
  (if-let [idx (dataset-index-attr ds attr-name)]
    #(instance-value-at % (int idx))
    (throw (Exception. (str "Could not find the attribute '" attr-name "' in the dataset!")))))

(defn randomize-dataset
  "Copies the given dataset and returns randomized version."
  ([ds] (randomize-dataset! (copy-dataset ds)))
  ([ds seed] (randomize-dataset! (copy-dataset ds) seed)))

(defn split-dataset
  "Splits the dataset into two parts based on either the ':percentage' given or the ':num' of instances.
The first dataset returned will have 'percentage ammount of the original dataset and the second has the
remaining portion. Both datasets are Delay objects that need to be dereffed.  If you want to have the
split immediately you can use do-split-dataset."
  [ds & [& {:keys [percentage num]}]]
  {:pre (= 1 (->> [percentage num] (remove nil?) count))} ;; must provide exactly one of these options
  ;; pattern matching would be really nice here...
  (if percentage
    [(delay (filters/remove-percentage ds {:percentage percentage :invert true}))
     (delay (filters/remove-percentage ds {:percentage percentage}))]
    [(delay (filters/remove-range ds {:range (str "first-" num) :invert true}))
     (delay (filters/remove-range ds {:range (str "first-" num)}))]))

(defn do-split-dataset
  "The same as split-dataset but actual datasets are returned and not Delay objects that need dereffing."
  [ds & options]
  (map deref (apply split-dataset ds options)))

(defn take-dataset
  "Returns a subset of the given dataset containing the first 'num' instances."
  [ds num]
  (filters/remove-range ds {:range (str "first-" num) :invert true}))

;; text-document datasets

(defn dataset-filename
  [model-prefix model-dir tag]
  (format "%s/instances/%s-%s.arff" model-dir model-prefix (name tag)))

(defn docs-to-dataset
  "Docs are expected to be maps with this structure: {:id
  [any], :has-class? [true/false], :title [string], :fulltext
  [string]}. Of course, title or fulltext could be nil. model-prefix
  is a filename prefix to saving/loading the model (necessary to
  initialize the string-to-wordvec filters), and model-dir is a folder
  to save/load the model.

  opts are optional parameters: :keep-n [int], :lowercase
  [true/false], :words-to-keep [int], :normalize [int], :transform-tf
  [true/false], :transform-idf [true/false], :stemmer
  [true/false], :resample [true/false], :training
  [true/false], :testing [true/false].

  A map is returned with structure {:dataset [the dataset], :docids
  [seq of docids as ordered in dataset]}."
  [docs model-prefix model-dir & opts]
  (let [parsed-opts (apply hash-map opts)
        original-ordering (map :id docs)
        docs-with-class (filter :has-class? docs)
        docs-without-class (let [dwoc (filter #(not (:has-class? %)) docs)]
                             (if (:resample parsed-opts)
                               (take (count docs-with-class) dwoc)
                               dwoc))
        docs-keep-n (if (:keep-n parsed-opts)
                      (concat (take (/ (:keep-n parsed-opts) 2) docs-with-class)
                              (take (/ (:keep-n parsed-opts) 2) docs-without-class))
                      (concat docs-with-class docs-without-class))
        docs-shuffled (shuffle (sort-by :id docs-keep-n))
        ds (make-dataset
            :docs [{:class [:no :yes]} {:title nil} {:fulltext nil}]
            (for [doc docs-shuffled]
              (let [orig-fulltext (:fulltext doc "")
                    fulltext (str/replace orig-fulltext #"\s+" " ")
                    fulltext-fixed (str/replace fulltext #"[^ \w\d]" "")
                    title (str/replace (:title doc "") #"[^ \w\d]" "")]
                [(if (:has-class? doc) :yes :no) title fulltext-fixed])))
        ds-title (let [f (filters/make-filter
                          :string-to-word-vector
                          {:dataset-format ds
                           :attributes [1]
                           :lowercase (:lowercase parsed-opts true)
                           :prefix "title-" :words-to-keep (:words-to-keep parsed-opts 1000)
                           :counts (:counts parsed-opts false)
                           :normalize (:normalize parsed-opts 0)
                           :transform-tf (:transform-tf parsed-opts true)
                           :transform-idf (:transform-idf parsed-opts true)
                           :stemmer (if (:stemmer parsed-opts false)
                                      "weka.core.stemmers.SnowballStemmer -S English")})]
                   ;; if testing, initialize the filter with the training instances
                   (when (:testing parsed-opts)
                     (let [ds-file (file (dataset-filename model-prefix model-dir :orig))]
                       (filters/filter-apply f (load-instances :arff ds-file))))
                   (filters/filter-apply f ds))
        ds-title-fulltext (let [f (filters/make-filter
                                   :string-to-word-vector
                                   {:dataset-format ds-title
                                    :attributes [1]
                                    :lowercase (:lowercase parsed-opts true)
                                    :prefix "fulltext-" :words-to-keep (:words-to-keep parsed-opts 1000)
                                    :counts (:counts parsed-opts false)
                                    :normalize (:normalize parsed-opts 0)
                                    :transform-tf (:transform-tf parsed-opts true)
                                    :transform-idf (:transform-idf parsed-opts true)
                                    :stemmer (if (:stemmer parsed-opts false)
                                               "weka.core.stemmers.SnowballStemmer -S English")})]
                            ;; if testing, initialize the filter with the training instances
                            (when (:testing parsed-opts)
                              (let [ds-file (file (dataset-filename model-prefix model-dir :title))]
                                (filters/filter-apply f (load-instances :arff ds-file))))
                            (filters/filter-apply f ds-title))
        ds-class (dataset-set-class ds-title-fulltext 0)]
    ;; if training, save unfiltered instances to re-initialize filter later
    (when (:training parsed-opts)
      (save-instances :arff (file (dataset-filename model-prefix model-dir :orig)) ds)
      (save-instances :arff (file (dataset-filename model-prefix model-dir :title)) ds-title))
    {:dataset ds-class :docids (map :id docs-shuffled)}))

