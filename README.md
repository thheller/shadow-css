# shadow-css

[![Clojars Project](https://img.shields.io/clojars/v/com.thheller/shadow-css.svg)](https://clojars.org/com.thheller/shadow-css)

CSS-in-Clojure(Script). `shadow.css` is essentially a mini DSL for writing CSS directly in Clojure(Script) Code, allowing you to directly write CSS where it is used.

Play with it live directly in your browser via the [shadow-grove Playground](https://code.thheller.com/shadow-grove-playground/0.4.0/).

Jump to:
- [Using the CSS macro](#usage)
- [Building CSS](#build)
- [Known Limitations / Trade-Offs](#limits)

## Status

The syntax for defining the CSS is **stable** and pretty much final, same goes for the `css` macro. I only recently started using all of this, so I won't rule out potential additions, but what is here now will very likely stay as it is forever.

The build side however is very early and very alpha. I expect things to change a lot here. Coming up with something that fits into existing CLJ(S) workflows is quite the challenge.

If you want to see actual projects using this you may look at the [shadow-cljs UI](https://github.com/thheller/shadow-cljs/tree/master/src/main/shadow/cljs/ui) sources. Just search for `(css`.

## Rationale

Writing and maintaining CSS is a burden. Editing CSS in actual `.css` files means you have to come up with names for everything so the HTML can actually reference the styles. Coming up with the names in the first place is hard, and maintaining them over time is even harder. Many naming conventions (eg. [BEM](http://getbem.com/)) exist, which helps shrink the problem but does not eliminate it. Writing actual CSS also requires constantly context switching since the syntax is much different from Clojure.

Nowadays, [tailwindcss](https://tailwindcss.com/) has become very popular alternative, which sort of flips the naming problem. Instead, you have a lot of predefined aliases for commonly used CSS properties and use those to style elements. This works great and using Tailwind in CLJ(S) projects actually works quite well. However, it does require installing JS tools and running them.

## Goals

I wanted something that ...

- **has close to zero (or actually zero) runtime impact and code size (for frontend CLJS builds)**
- gives me the expressive power of Tailwind aliases, while still giving me full access to all of CSS
- stays entirely in the CLS(S) space with no outside dependencies (`node` not required)
- is usable in all CLJ(S) projects and libraries
- is completely framework-agnostic, with no expectations for how the HTML/DOM is actually generated
- integrates seamlessly with CSS written by other means

It is not a goal to hide or abstract CSS in any way other than a slightly friendlier syntax and developer experience.

It is also not a goal to express every single thing CSS can potentially do. Sometimes, it is easier to just write some CSS (eg. [@keyframes](https://developer.mozilla.org/en-US/docs/Web/CSS/@keyframes)). The build tooling makes it easy to include basic CSS directly.

<a name="usage"></a>
# Using the CSS macro

*Knowledge of [CSS](https://developer.mozilla.org/en-US/docs/Web/CSS) is required to make anything meaningful with this. No CSS explanation is done here. There are no predefined "components".*

The basis for everything is the `shadow.css/css` macro. It accepts a subset of Clojure to define the CSS and returns a classname for use in place of HTML `class` attribute

```clojure
(ns my.app
  (:require [shadow.css :refer (css)]))

(defn hiccup-example []
  [:div {:class (css :px-4 :shadow {:color "green"})}
   "Hello World"])
```

This will generate HTML like `<div class="my_app__L5C16">Hello World</div>`. The generated classname is derived from the location used in the code, but may be optimized later by the build tools. The generated name is of no concern when writing the code, you can ignore it entirely. This eliminates the naming problem. Moving or deleting the `(css ...)` rule also means the CSS is updated accordingly.

The generated CSS for the above will look something like this.

```css
.my_app__L5C16 {
  box-shadow: 0 1px 3px 0 rgb(0 0 0 / 0.1), 0 1px 2px -1px rgb(0 0 0 / 0.1);
  color: green;
  padding-left: 1rem; 
  padding-right: 1rem;
}
```

Note that this is subject to change once optimizations are applied. The nice thing is that is something that will just happen automatically, and is not something you need to worry about yourself.

### Naming sometimes useful

Naming things is sometimes useful. Instead of naming the CSS class, we just use Clojure for that. So, for example we can just use `let` to define local bindings. Each name declared that way is just referencing a string holding the generated classname.

```clojure
(defn hiccup-example [key val]
  (let [$row (css ...)
        $key (css ...)
        $val (css ...)]
    [:div {:class $row}
     [:div {:class $key} key]
     [:div {:class $val} val]]))
```

`(def $my-button (css ...))` of course also works if you want to share styles in multiple places.

I personally prefer prefixing css classnames with `$` in names, just to distinguish them easily in the code. This is of course entirely optional, it is just a regular CLJ(S) symbol with no special meaning otherwise.

## CSS Syntax

As mentioned earlier the `css` macro only accepts a subset of the Clojure. It must be entirely static and cannot take any dynamic code. Symbols or lists used inside `css` will result in an error. You'd have the same limitation when using actual CSS files, so you lost nothing.

The syntax is limited to the following, and each element is merged in order from left to right. Later values will override earlier values, in case they define the same properties.

### Keywords

```clojure
(css :px-4 :shadow :flex)
```

Keywords represent aliases. For the most part they are identical to tailwind aliases, you can refer to the excellent tailwind documentation (eg. [px-4](https://tailwindcss.com/docs/padding)). They are just shortcuts for common CSS property values.  When the CSS is generated each alias is replaced with its value. `:px-4` is just short for `{:padding-left 4 :padding-right 4}`. There are thousands of pre-defined aliases, and you may define your own. They are also entirely optional, you can just use maps if you want.

### Maps

```clojure
(css :px-4 {:color "green"})
```

Each key in a map must be a keyword, which maps directly to a CSS property name. Their name is just passed though as-is and no conversion is done. Conveniently, CSS uses the same name notation and everything maps to idiomatic Clojure names naturally (eg. `:padding-left` is `padding-left` in CSS).

The value for each key can be
- A string which is used as-is. The example above just generates `color: green;` in the CSS. They are not converted in any way.
- Numbers are converted with some basic rules, these rules are shared with the aliases. `(css :p-4)` is identical to `(css {:padding 4})`, which both end up as `padding: 1rem;` in the CSS. They map exactly to the default [spacing scale used by tailwind](https://tailwindcss.com/docs/customizing-spacing#default-spacing-scale). Note that most numbers in CSS require a unit, so you use Strings in their place. `:padding 4` does not mean `4px`. You'd use `:padding "4px"` for that.
- Keywords are again aliases. `(css {:color :primary-color})` allows you to define a `:primary-color` alias when building the CSS, instead of hardcoding the value in the code.

### Strings

```clojure
(css "my-class" :px-4)
```

Strings are used as pass-through values and will not affect the CSS generated by `shadow.css`. It will however affect the string generated by the `css` macro. This is a useful shortcut if you want to integrate with other existing CSS.

```clojure
[:div {:class (css "foo bar" :px-4)} "Hello!"]
;; same as 
[:div {:class (str "foo bar " (css :px-4))} "Hello!"]
```
generates
```
<div class="foo bar my_app__LxCx">Hello!</div>
```

### Vectors

```clojure
(css
  {:color "green"}
  [:hover {:color "red"}])
```

Vectors represent sub-selectors and can be used to target pseudo-elements, nested elements or media queries.

The first element is either string or a keyword, which again is a pre-defined aliases that will be replaced by its value when building. A String must either start with a `@` when representing a media query or contain a `&`, which will refer back to the actual generated classname.

`:hover` in the above maps to `"&:hover"` which in the final CSS would be `.my_app__LxCx:hover { ... }`.

Media Queries are just passed through and the emitted CSS rules are grouped accordingly.

```clojure
(css :px-4 ["@media (min-width: 1024px)" :px-8])
;; or using predefined alias
(css :px-4 [:lg :px-8])
```

Writing those repeatedly would get annoying, so the default [tailwind responsive breakpoints](https://tailwindcss.com/docs/responsive-design) are provided as aliases by default. Which of course can be overridden or extended via custom aliases.

Each element in the vector following the first will be part of that sub-selector and only accepts keyword aliases, maps or other vectors. Pass-through Strings are not allowed here.

## Dynamic Uses

By design the `css` macro is very static and does not accept any dynamic values. This does not mean we can't do dynamic things. We are still just writing Clojure code, we can just use other means to gain back all dynamic things we may need.

There are many options to choose from and all resemble exactly what you'd be doing with regular CSS. You could just replace all `(css ...)` here with a `"my-class"` referencing CSS defined in actual CSS files, and the code would otherwise stay the same.

```clojure
;; assigning different classnames based on arguments
(defn ui-example [selected?]
  (let [$selected (css ...)
        $regular (css ...)]
    [:div {:class (if selected? $selected $regular)}
     ...]))

;; adding additional rules based on argument
(defn ui-example [selected?]
  (let [$base (css ...)
        $selected (css ...)]
    [:div {:class (str $base (when selected? (str " " $selected)))}
     ...]))

;; depending on what you use to generated html that may already have
;; convenience helpers for you to make it a little less verbose
;; remember that all you are working with here is a string
(defn ui-example [selected?]
  (let [$base (css ...)
        $selected (css ...)]
    [:div {:class [$base (when selected? $selected)]}
     ...]))

;; using a sub-selector
;; &.selected here targets elements having BOTH the selected and & classes
;; remember that & here is replaced with the actual classname the css macro
;; generates. So it is identical to a .foo.selected {} selector in CSS
(def $thing
  (css
    ...
    ["&.selected"
     ...]))

(defn ui-example [selected?]
  ;; so the code just conditionally assigns the two classnames
  [:div {:class (str $thing (when selected? " selected"))}
   ...])

;; using style attributes
(defn ui-example [selected?]
  [:div {:class (css ...)
         :style {:color (if selected? "red" "green")}}
   ...])
```

You can also use [CSS Variables](https://developer.mozilla.org/en-US/docs/Web/CSS/Using_CSS_custom_properties) of course. I won't go into this here further since that would blow the scope of this document.

I think all dynamic uses are covered just fine and the `css` macro itself doesn't need to do any of it.

## Why so static?

Doing anything more dynamic would require doing things at runtime.

This violates the first stated goal, since it would require a substantial amount of code to actually generate the CSS at runtime. Instead, all CSS is generated by parsing and extracting `(css ...)` forms from actual source code files on disk. It becomes part of you build process, and you are left with generated ready to use `.css` files.

This also means it is easy to integrate into existing CSS build systems. No need to throw away all your existing styles.

It suffers none of the known issues that other systems, which build CSS at runtime, have. You are paying for the generation cost at build time. Instead of your users paying it every time they open your webpage. Leading to substantially better performance overall.

<a name="build"></a>
# Building CSS

All of this is subject to change. Currently, the only way to build the CSS is by writing some Clojure code. This is very rough but it works fine.

I'd suggest using a new namespace since the `shadow.css.build` namespace is not needed at runtime and should not be part of your regular builds.

## Release Builds

```clojure
(ns build
  (:require
    [shadow.css.build :as cb]
    [clojure.java.io :as io]))

(defn css-release [& args]
  (let [build-state
        (-> (cb/start)
            (cb/index-path (io/file "src" "main") {})
            (cb/generate
              '{:ui
                {:include
                 [my.app*]}})
            (cb/write-outputs-to (io/file "public" "css")))]

    (doseq [mod (:outputs build-state)
            {:keys [warning-type] :as warning} (:warnings mod)]

      (prn [:CSS (name warning-type) (dissoc warning :warning-type)]))))
```

`(cb/start)` creates the initial build state and creates all the default aliases. `cb/index-path` finds all `.clj`, `.cljs`, `.cljc` files and extracts `(css ...)` forms from it. The `cb/generate` generates the CSS according to the configuration passed into it. Currently, this only takes `:include` which specifies a list of namespace symbols to generate CSS for. They may use a `*` wildcard at the end to include all namespaces matching that. `cb/write-outputs-to` writes the actual `.css` files to the supplied dir. It generates a `public/css/ui.css` in this case.

The `doseq` is for printing warnings (eg. missing aliases), also rough but works for now.

You may run this from the REPL, `lein run -m build/css-release`, `shadow-cljs run build/css-release` or `clj -X build/css-release` (additional aliases may be necessary).

## Development Builds

The above works totally fine but is a bit manual. During development I prefer to just have something watching my source files and automatically rebuilding my CSS on change. This coupled with the hot-reload for CSS provided by `shadow-cljs`  (or `figwheel`) makes for a very nice workflow.

Currently, the `shadow.css.build` namespace has nothing to provide this. I use this basic construct to integrate the CSS building into my regular REPL workflow. This is using the `fs-watch` utility provided by `shadow-cljs`, but any file watcher will do.

```clojure
(ns repl
  (:require
    [clojure.java.io :as io]
    [shadow.css.build :as cb]
    [shadow.cljs.devtools.server.fs-watch :as fs-watch]))

(defonce css-ref (atom nil))
(defonce css-watch-ref (atom nil))

(defn generate-css []
  (let [result
        (-> @css-ref
            (cb/generate '{:ui {:include [my.app*]}})
            (cb/write-outputs-to (io/file "public" "css")))]

    (prn :CSS-GENERATED)
    (doseq [mod (:outputs result)
            {:keys [warning-type] :as warning} (:warnings mod)]
      (prn [:CSS (name warning-type) (dissoc warning :warning-type)]))
    (println)))

(defn start
  {:shadow/requires-server true}
  []
  
  ;; first initialize my css
  (reset! css-ref
    (-> (cb/start)
        (cb/index-path (io/file "src" "main") {})))

  ;; then build it once
  (generate-css)

  ;; then setup the watcher that rebuilds everything on change
  (reset! css-watch-ref
    (fs-watch/start
      {}
      [(io/file "src" "main")
       (io/file "src" "dev")]
      ["cljs" "cljc" "clj"]
      (fn [updates]
        (try
          (doseq [{:keys [file event]} updates
                  :when (not= event :del)]
            ;; re-index all added or modified files
            (swap! css-ref cb/index-file file))

          (generate-css)
          (catch Exception e
            (prn :css-build-failure)
            (prn e))))))

  ::started)

(defn stop []
  (when-some [css-watch @css-watch-ref]
    (fs-watch/stop css-watch)
    (reset! css-ref nil))

  ::stopped)

(defn go []
  (stop)
  (start))
```

In the REPL I can run `(repl/start)` to start the automatic building and `(repl/stop)` to stop it. If you are used to the common CLJ REPL workflow setups this should fit right in.

Since I also run `shadow-cljs` I usually start my work by running `npx shadow-cljs run repl/start`. That'll start `shadow-cljs` and CSS building starts with it. You could start CLJS builds from the `start` function too, I just do that via the shadow-cljs UI.

Note that all of this could just call `(build/css-release)`. It absolutely does not need to do all of this extra stuff, but it is a bit faster since it does less on each file change. Creating the initial build state and indexing all files is more expensive than just incrementally updating changed namespaces.

For the interested the build state is just a map, you can modify it however you like. Adding or overriding an alias is just `(assoc-in build-state [:aliases :px-4] {:color "green"})`. Feel free to explore, eg. via `tap>` in the shadow-cljs Inspect UI.

<a name="limits"></a>
# Known Limitations / Trade-Offs

## REPL

I don't think this is actually a problem, but might be for some REPL-heavy workflows.

All CSS is built from actual files on disk. It cannot possibly see what you do at the REPL. Remember this does almost nothing at runtime. Everything you write and load from disk works just fine (eg. `require` and `load-file`) but evaluating individual forms may become a problem. You may of course evaluate `(css ...)` forms at the REPL, they'll however be somewhat useless since no actual CSS is generated. Not that any REPL needs CSS anyways.

```
(require '[shadow.css :refer (css)])
=> nil
(css :px-4)
=> "user__L1_C1"
```

The problem shows itself when you put something in a file but then redefine it at the REPL. Suppose, somewhere you have:

```clojure
(defn ui-component []
  (html [:div {:class (css :px-4)} "Hello World"]))
```

change something and re-define it at the REPL, without saving the file to disk. The location of the `(css` form may have changed and therefore generated classname no longer matches. When the HTML is loaded it'll point to a classname that doesn't exist and the element may appear unstyled.

Again, I don't believe this to be a problem, and is a trade-off I'm willing to make either way.


## Static Code Analysis

The current build tooling just looks for `(css ...)` forms in source files. It just assumes that you have a `:refer (css)` and that all `(css ...)` uses are actual CSS forms it should process. It could be a little smarter and respect qualified uses or other aliases, but doesn't as of now.

Since the code is not evaluated during builds it also cannot find any CSS generated by other macros. There are ways those macros could provide the necessary data in theory, but it cannot be inferred automatically by the current tooling.

I also don't believe this to be a problem, but I'm eager to see someone come up with something shorter/friendlier than the current `(css ...)` forms.