cbfg
====

An experiment / prototype in distributed systems modeling,
simulation and (perhaps one day) code-gen.

This was initially discussed over lunch at a Five Guys burger
restaurant, so "c.b.f.g." means couchbase five guys.  Clearly, this is
a backronym waiting for future reassignment.

# grab the code...

    git clone git@github.com:couchbaselabs/cbfg.git

# for cbfg developers...

## grab the dependencies...

    git clone git@github.com:couchbaselabs/ago.git

## setup the dependencies...

    cd cbfg
    mkdir checkouts
    cd checkouts
    ln -s ../../ago .
    cd ..

# build...

You'll need the [lein](http://github.com/technomancy/leiningen)
toolchain, so that you can run...

    lein cljsbuild once cbfg

Or, during development cycle, instead run...

    lein cljsbuild auto cbfg

# run a local webserver...

    $ python -m SimpleHTTPServer
    Serving HTTP on 0.0.0.0 port 8000 ...

# browse...

    http://localhost:8000
    http://localhost:8000/world-t1.html
    http://localhost:8000/world-t2.html

# for some codegen...

    lein run src/cbfg/fence.cljs

