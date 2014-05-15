cbfg
====

An experiment / prototype in distributed systems modeling,
simulation and (perhaps one day) code-gen.

Also, it was initially discussed over lunch at (f)ive (g)uys,
so "c.b.f.g." (couchbase five guys).  Clearly, this is a
future backronym waiting to be reassigned.

# build

get [lein](http://github.com/technomancy/leiningen)

    lein cljsbuild once cbfg

open index.html

# convert

    lein run src/cbfg/fence.cljs
