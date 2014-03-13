This framing.spec file covers network protocol framing.

The protocol is asymmetric.  Although requests and responses have
similar header and body framing, there is a clear distinction between
server and client sides of a connection.  A server cannot send a
message to a client "out of the blue".  Instead, responses (if any,
and there might be more than one response message to a request
message) must be due to an earlier, corresponding client request.

  message
  - request vs response first byte
  - messageFlags (uint8)
    - oneWay (never a response (such as for ALIVE / TICK msgs))
      vs quiet (perhaps zero or 1 errorResponse)
      vs responseExpected
      vs responsesExpected
    - fencedMessage vs lastMessage vs neither
  - opCode
  - opaqueId (uint64)
  - channelId (string)
  - header*
  - body*

  header
  - covers application specific metadata about processing the message
  - header information is (logically) not persisted
  - compression
  - dataType
  - request timeout
  - want CCCP
  - proceed only if caught up to X seqId
  - txId
  - writeConcern
  - uncompressed value size (for stats)
  - partitionId
  - checkSum

  errorResponseBody
    errorCode
    msg
    extraBody*

  opCode (uint8)
  - QUIT
  - NOOP
  - AUTH_CHANNEL
  - CLOSE_CHANNEL
  - VERSION (also returns capabilities)
  - HEARTBEAT_WANTED (or header flag?)
  - HEARTBEAT

  errorCode (uint16)
  - UNEXPECTED
  - UNKNOWN_OPCODE
  - UNSUPPORTED_OPCODE
  - TOO_MANY_CHANNELS
  - AUTH_CHANNEL_FAILED

channel
  channelId
  channelPriority
    - affects which messages are processed first
    - and downstream tasks should inherit channel priority
      - example: high priority backfill or changes stream
  authedUser (default: _anonymous)
  inflightMessage*
  pausedInputProcessing
    when too many msgs
    maxChannelMsgs
    maxChannelBytes
    a channel with too many msgs
      sends back error msg so that client can retry?
      (or, alternative answer, should it just block the entire conn?)
  fenced
    waiting for fenced reply so no out-of-order replies allowed

conn
  do we need these?
    maxInflightMsgs
    maxInflightBytes
    should these send back error?  or block the entire conn?

inflightMessage
  startAtHRTime
  endAtHRTime
  nextMessage
  prevMessage
  opaqueId
  op
  requestHeader*
  requestChunk* (lazy?)
    start processing even before all request chunks are received

(need to handle "stats" or other open requests with multiple responses)

how to handle flow control?
  e.g., scan / changes-stream iteration
  but, receiver is slow
  idea: occasionally send receiver a ack-request
    receiver answers (perhaps on another channel) with ack-responses
    consider another (dedicated) channel as first channel
      might be paused with too many msgs

what if a conn closes?
- need to have engineer internals stop any in-flight request processing.

what if a channel closes?
- need to have engineer internals stop any in-flight request processing.
- a channel close is also implicitly fenced
-- so, there will not be any new responses on that channel
   after the channel-close is processed.

should there be explicit channel open?

what if channel is immediately reopened?
- no problem, the semantics are clear with the fenced channel-close.

requests & responses / green stack
- responses might out of order
- up to client to re-sequence the responses, such as by using opaqueIds.

proxy-ability
- requests have an optional opaqueId
- proxy keeps a opaqueId(client) <-> opaqueId(proxy) map
- proxy might run out of opaqueIds? (opaqueIds need to be big enough)
- fence request (or request flag) to make sure all responses are
  on the wire before handling more requests.
-- if proxy uses fence, it will block up a connection
-- answer: level of indirection via channels
--- allow for optional channelId in request "header"

fenced request means server does not process request until
previous responses are on the wire.  requests might be received
by not handled.

beware: too many inflight requests in a single channel might block up
the entire conn.

issue: DEADLOCK avoidance -- sceneario: if server sends a RUALIVE
but the channel or the conn is full with other requests already...
- solution: for channel full case, client should use a new channel
  for every big, slow scan (or request that may have lots of response
  msgs) so the channel is never full and can be open for IAMALIVE pings.
- solution: for conn full case, timeouts on server side...
  ERR_RUALIVE_TIMEOUT (and conn was paused due to full-ness).
  - eventually, the client needs to open more than one conn.

$ lein repl
  nREPL server started on port 51044 on host 127.0.0.1
  REPL-y 0.3.0
  Clojure 1.5.1
    Docs: (doc function-name-here)
          (find-doc "part-of-name-here")
  Source: (source function-name-here)
 Javadoc: (javadoc java-object-or-class-here)
    Exit: Control+D or (exit) or (quit)
 Results: Stored in vars *1, *2, *3, an exception in *e
  user=> (require 'cbfg.fence)
  nil
  user=> (in-ns 'cbfg.fence)
  #<Namespace cbfg.fence>
  cbfg.fence=> (test-rq-processor)
  nil
  cbfg.fence=> Got result:  6
  Got result:  3
  Got result:  12
  Got result:  11
  Got result:  11
  Got result:  11

