inspirations
------------

http://thesecretlivesofdata.com/raft/

https://github.com/couchbaselabs/xdsim

https://github.com/couchbaselabs/cbgm

ideas
-----

Visualize and simulate the complex system _before_ you build it.

An interactive model simulator and visualization explains and
convinces better than a static spec, especially compared to a
wall-of-words document that nobody will read.

Use higher level abstractions for encoding rules & model...

* DSL's
* state machines
* boom-like approaches
* declarative approaches

Rules to encode a simulator might be reusable...

* as parts of a final system
* codegen and multi-platform targets
* fuzzer / checker / test-case generator
* presentations / docs
* stats monitoring U/I
* basic CRUD U/I

U/I visualization ideas
-----------------------

U/I to follow (or "star" or "bookmark") a key or value (or
sub-field/value) and watch it flow through the system.

Watch write dedupe / read dedupe.
- see persistence & replication dedupe in action
- see read batching & convoying in action

U/I to follow a particular revId of an item.

U/I to follow several of the above.

Snapshot reads.

Show rebalance, failover logs, rollbacks.

Watch what happens to concurrent mutation races,
and "fork" the U/I to watch both outcomes of the race.

Watch how a transaction works.

Show where stats come from.

Show the impact of a lock.

Show different outcomes of distribution changes.

Machines?  Drives?

code-gen
--------

Keep in mind platforms like...

- server
-- linux
--- docker
-- osx
-- solaris
-- windows
-- javascript/nodejs

- mobile
-- android
-- ios
-- javascript/sqlite

- client / app platforms

- router / proxy platforms

miscellaneous model ideas/notes
-------------------------------

rules...

rebalance is the most important feature
- even at the cost of slower ops
- rebalance, like car brakes, must work in emergencies and under duress

uniform naming
- know where stats come from
- allow stats to be 'git grep'-able all the way to the source

----------------------------------------
rocksdb thoughts

pros
- merge operator
- online backup
- lots of operational features

cons
- memory mgmt control (although Put has SliceParts)
- managing values as 4KB chunks
- view aggregates might not work with LSM/leveldb? use rocksdb merge?

----------------------------------------
workload generator

input
- workload ops mixes
- collection capabilitiies
- cluster config (multicluster)

----------------------------------------
operational

keep in mind hot-loading / hot-reloading code

----------------------------------------
stuff to look at / learn

- clojure.test
- hiccup ~ html DSL
- clojure slurp API
- clojure.java.io
-- io/resource API
- engelberge/instaparse for clojure
- total programing
- idris
- brett victor
- lighttable
-- http://www.lighttable.com/2014/05/16/pain-we-forgot/
- TLA+
-- http://research.microsoft.com/en-us/um/people/lamport/tla/amazon.html
- ocaml combinators for declarative distributed systems, "opis"
- https://github.com/pbailis/ramp-sigmod2014-code

Aliaksey K. mentions...
- SCTP has approaches for higher efficiency?
-- some ways to reduce syscalls?
- SPDY / HTTP2 has flow control parts?
- reducing conns/ports via multi-channels per conn (lanes)
  isn't a good idea; "don't bother"; consider instead
  user-space network/TCP stacks?

---------------------------
For stateful AUTH (digest/challenge instead of PLAIN)...

...need to have a AUTH_NEEDED error code?

Imagine proxy case where proxy is accessing multiple servers
due to some large range scan, proxy needs to create conns
and lanes on demand, and half-way through it needs to do
digest/challenge AUTH.

Ideas include needing super-user capability for proxy;
but, that's not great for client-side tier of proxies.

Or, AUTH_NEEDED negotiation in middle of request, where
proxy & client redo AUTH on some other lane.  But, that's
complicated and has head-of-line issues.

Or, proxy needs to grab all it's conns and lanes up-front;
and, any AUTH_NEEDED negotiations need to happen up-front
before the real work.

