(ns pro.juxt.krei.core
  (:require
    [figwheel-sidecar.repl-api :as repl-api]
    [clojure.java.classpath :as classpath]
    [juxt.dirwatch :as dirwatch]
    [me.raynes.fs :as fs]
    [clojure.string :as string]
    [clojure.java.io :as io]
    [sass4clj.core :as sass]
    [clojure.edn :as edn]
    [cljs.build.api]
    [pro.juxt.krei.impl.util :refer [deleting-tmp-dir]]))

(defn- list-resources [file]
  (enumeration-seq
    (.getResources
      (.. Thread currentThread getContextClassLoader)
      file)))

(defn find-krei-files
  []
  (list-resources "krei-file.edn"))

(defn read-krei-files
  []
  (map (comp clojure.edn/read #(java.io.PushbackReader. %) io/reader)
       (pro.juxt.krei.core/find-krei-files)))

(defn build
  []
  ;; figwheel can't handle the deleting of this directory, and just blows up,
  ;; so leave stale files hanging around, it'll be fine, he says.
  ;; (fs/delete-dir "./target/public/css/")
  (let [krei-files (read-krei-files)]
    (run!
      (fn [[input-file relative-path]]
        (sass/sass-compile-to-file
          input-file
          ;; TODO: How do I choose where to dump files?
          ;; TODO: (Maybe) take an option for the subpath to build CSS into?
          (io/file "./target/public/css"
                   (string/replace relative-path #"\.scss$" ".css"))
          ;; TODO: Take options
          {}))
      (eduction
        (map :krei.sass/files)
        cat
        (map (juxt (comp io/resource) identity))
        krei-files))))

(defn watch
  "Returns a function which will stop the watcher"
  ;; TODO: Watch krei files & reconfigure figwheel on changes.
  []
  (let [target (.toPath (io/file "target"))
        target-relative #(.resolve target %)
        krei-files (read-krei-files)

        classpath-dirs (remove
                         ;; Filter out build directory, as it's on the classpath in dev
                         #(= (.toPath %) (.toAbsolutePath target))
                         (classpath/classpath-directories))

        krei-builders (mapv
                        (fn [path]
                          (dirwatch/watch-dir (fn [p]
                                                (println p)
                                                (build))
                                              (io/file path)))
                        classpath-dirs)]
    ;; TODO: Update default config with target location
    (repl-api/start-figwheel!
      {:figwheel-options {:css-dirs [(str target)]}
       :all-builds (into []
                         (comp (map :krei.figwheel/builds)
                               cat
                               (map #(assoc % :source-paths (map str classpath-dirs)))
                               (map #(update % :compiler merge {:optimizations :none}))
                               (map #(update-in % [:compiler :output-dir] (comp str target-relative)))
                               (map #(update-in % [:compiler :output-to] (comp str target-relative))))
                         krei-files)})
    (build)
    (fn []
      (run! dirwatch/close-watcher krei-builders)
      (repl-api/stop-figwheel!))))

(defn prod-build
  [classpath-output]
  (let [build-data (deleting-tmp-dir "prod-build-data")
        krei-files (read-krei-files)]
    (run!
      (fn [[input-file relative-path]]
        (sass/sass-compile-to-file
          input-file
          (-> classpath-output
              ;; TODO: Take an option for the subpath to build CSS into
              (.resolve "public/css")
              (.resolve (string/replace relative-path #"\.scss$" ".css"))
              (.toFile))
          ;; TODO: Take options
          {}))
      (eduction
        (map :krei.sass/files)
        cat
        (map (juxt io/resource identity))
        krei-files))
    (run!
      #(cljs.build.api/build (mapv str (classpath/classpath-directories)) %)
      (into []
            (comp (map :krei.figwheel/builds)
                  cat
                  (map :compiler)
                  (map #(assoc %
                               :optimizations :advanced
                               :source-map false
                               :closure-defines {'goog.DEBUG false}
                               :output-dir (str (.resolve build-data "cljs"))))
                  (map (fn [c] (update c
                                       :output-to
                                       #(str (.resolve classpath-output %))))))
            krei-files))))
