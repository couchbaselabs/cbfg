Map collection objects are commonly passed around to hold request and
response data, and this page has some notes on request and response
standard codes, keys and values.

Request maps should include an :op code and a :res-ch (response
channel).

The "server" or request processing entity is expected to close the
res-ch when all responses (there might be multiple responses) for the
request have been put onto the res-ch.  The requestor can rely on the
closed res-ch to determine that the entire request has been handled.

The requestor can use an :opaque key/value in the request map to
tie multiple responses together, and the server must mirror back the
:opaque key/value in all its response maps.

Response maps have these key/value entries...

 :status      - <common status code like :ok, :invalid, :mismatch ...>
 :status-info - <optional, extra human readable message or information
                 meant to aid with debugging>
 :more        - <optional, boolean, defaults to false.
                 true means more responses for the request will follow;
                 and false means this is the last response for the request>

Response maps must include a :status key/value entry.

If appropriate, an optional :status-info key/value entry can hold more
detailed information for the client / requestor / developer.  That is,
the :status-info value is often meant for logging and human debugging
as opposed to programmatic processing (think HTTP human-readable
status message strings instead of HTTP status codes).  A :status-info
value might also be perhaps an opaque string or JSON or EDN.

Other standardized response keys/values should be used to convey
program-readable data (like :value, etc).

The :more boolean response key/value entry signifies a non-terminating
response, when a stream of multiple responses is expected.  Some
examples might be streaming the next entry during a directory listing,
range scan, or multi-get / multi-change request.  For example, there
should be a sequence of zero or more responses with :more of true,
terminated by a single, final response with :more of false.

The standard, common :status codes...

:ok

This means a successful request.  Additional response data may be
available in other response keys/values like :value.

:invalid

Caller made an ill-formed request (such as missing a parameter).
More information may be available in the :status-info key/value.

:failed

Means some server-side failure, as opposed to invalid request inputs,
similar to 5xx HTTP.

:full

A more specific type of failure where some resource quota or limit is
reached, and without some explicit action from user (such as deleting
resources or configuring more capacity) the fullness will continue.

:temp-full

A type of failure where some resource limit was reached, but just by
waiting, backing off, and retrying later, the same request might
succeed at a later time.  For example, persistence and/or replication
queues might be temporarily full and take time to drain.

:mismatch

Caller made a correctly formed request, but which isn't currently
matching server state (but perhaps the request might have been correct
at a different time).  For example, this can be used for a CAS or UUID
mismatch, and is useful for detecting A-B-A issues.

:exists

Caller tried to create a resource, but it already exists.

:not-found

Caller made a correctly formed request, but the server-side resource
no longer exists, perhaps due to a previous deletion.  In contrast, a
:mismatch informs the caller that a named resource exists, but has a
version, CAS or UUID mismatch.

:not-authenticated
:not-authorized

----------------------------------

:redirect or :moved ? (covers not-my-vbucket / not-my-partition?)

:not-enough-resources ? (covers temp-OOM?) or :busy
