# shadow-css

CSS-in-Clojure(Script). `shadow.css` is essentially a mini DSL for writing CSS directly in Clojure(Script) Code, allowing you to directly write CSS where it is used.

## Rationale

Writing and maintaining CSS is a burden. Editing CSS in actual `.css` files means you have to come up with names for everything so the HTML can actually reference the styles. Coming up with the names in the first place is hard, and maintaining them over time is even harder. Many naming conventions (eg. [BEM](http://getbem.com/)) exist, which helps shrink the problem but does not eliminate it. Writing actual CSS also requires constantly context switching since the syntax is much different from Clojure.

Nowadays, [tailwindcss](https://tailwindcss.com/) has become very popular alternative, which sort of flips the naming problem. Instead, you have a lot of predefined aliases for commonly used CSS properties and use those to style elements. This works great and using Tailwind in CLJ(S) projects actually works quite well. However, it does require installing JS tools and running them.

## Goals

I wanted something that ...

- **has close to zero (or actually zero) runtime impact and code size (for frontend CLJS builds)**
- gives me the expressive power of Tailwind aliases, while still giving me full access to all of CSS
- stays entirely in the CLS(S) space with no outside dependencies
- is usable in all CLJ(S) projects and libraries
- is completely framework-agnostic, with no expectations for how the HTML/DOM is actually generated
- integrates seamlessly with CSS written by other means

It is not a goal to hide or abstract CSS in any way other than a slightly friendlier syntax and developer experience.

It is also not a goal to express every single thing CSS can potentially do. Sometimes, it is easier to just write some CSS.

# Usage

*Knowledge and Understanding of [CSS](https://developer.mozilla.org/en-US/docs/Web/CSS) is required to make anything meaningful with this. No CSS explanation is done here.*

The basis for everything is the `shadow.css/css` macro. It accepts a subset of Clojure to define the CSS and returns a classname for use in place of HTML `class` attribute

```clojure
(ns my.app
  (:require [shadow.css :refer (css)]))

(defn hiccup-example []
  [:div {:class (css :px-4 :shadow {:color "green"})}
   "Hello World"])
```

This will end as generating some HTML like `<div class="my_app__L5C16">Hello World</div>`. The generated classname is derived from the location used in the code, but may be optimized later by the build tools. The generated name is of no concern when writing the code, you can ignore it entirely. This eliminates the naming problem. Moving or deleting the `(css ...)` rule also means the CSS is updated accordingly.

However, naming is sometimes useful. Instead of naming the CSS class, we just use Clojure for that. I personally prefer prefixing css classnames with `$` in names, just to distinguish them easily later. This is of course entirely optional, it is just a regular CLJ(S) symbol with the special meaning otherwise.

So, for example we can just use `let` to define local bindings. Each name declared that way is just referencing a string holding the generated classname.

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

## Syntax

As mentioned earlier the `css` macro only accepts a subset of the Clojure Syntax. It must be entirely static cannot take any dynamic code. Symbols or lists used inside `css` will result in an error. You'd have the same limitation when using actual CSS files, so you lost nothing.

The syntax is limited to the following:

### Keywords

```clojure
(css :px-4 :shadow :flex)
```

Keywords represent aliases. For the most part they are identical to tailwind aliases, you can refer to the excellent tailwind documentation (eg. [px-4](https://tailwindcss.com/docs/padding)). They are just shortcuts for common CSS property values.  When the CSS is generated each alias is replaced with its value. `:px-4` is just short for `{:padding-left 4 :padding-right 4}`. There are thousands of pre-defined aliases, and you may define your own.

### Maps

```clojure
(css {:color "green"})
```

Each key in a map must be a keyword, which maps directly to a CSS property name. Their name is just passed though as-is and no conversion is done. Luckily, CSS uses the same name notation and everything maps to idiomatic Clojure names naturally (eg. `:padding-left` is `padding-left` in CSS).

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

Vectors represent subselectors and can be used to target pseudo-elements, nested elements or media queries.

The first element is either string or a keyword, which again is a pre-defined aliases that will be replaced by its value when building. A String must either start with a `@` when representing a media query or contain a `&`, which will refer back to the actual generated classname.

`:hover` in the above maps to `"&:hover"` which in the final CSS would be `.my_app__LxCx:hover { ... }`.

Media Queries are just passed through and the emitted CSS rules are grouped accordingly.

```clojure
(css :px-4 ["@media (min-width: 1024px)" :px-8])
;; or using predefined alias
(css :px-4 [:md :px-8])
```

Writing those repeatedly would get annoying, so the default [tailwind responsive breakpoints](https://tailwindcss.com/docs/responsive-design) are provided as alias by default. Which of course can be overridden or extended via custom aliases.

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

# Building

TBD