Some notes on the generic :status/:status-info and other response
codes, keys and values.

Responses should include a :status code.  And, if appropriate, an
optional :status-info value which holds more detailed information
for the client / developer.

The :status-info value, however, is just meant for logging and human
debugging and not really meant to be parsable by computers.  It might
be perhaps an opaque string or JSON or EDN.

Other standardized response keys/values should be used, instead, to
convey program-readable information (like :result, :value, :partial,
etc).

The :status codes...

:ok

More response information may be available in other response
keys/values like :result or :value.

:invalid

Caller made an ill-formed request (such as missing a param).

:mismatch

Caller made a correctly formed request, but which isn't currently
matching server state (but perhaps the request might have been correct
at a different time).  For example, this can be used for a CAS or UUID
mismatch, and is useful for detecting A-B-A issues.

:not-found

Caller made a correctly formed request, but the server-side resource
no longer exists, perhaps due to a previous deletion.  In contrast to
a :not-found status code, a :mismatch informs the caller that a
resource is still there, but has a version or CAS mismatch.

:partial

This :status code signifies a partial response, when multiple
responses are expected.  Some examples might be streaming the next
entry in a directory listing, or a range scan, or a changes-stream
request, or a multi-get / multi-change request.  For example, there
might be a sequence of many :partial responses, terminated by a final
non-partial :status response code (like ok or some error code).  In a
partial response, there might be an additional :partial code with more
program-readable data.

:redirect or :moved ? (covers not-my-vbucket / not-my-partition?)

:not-enough-resources ? (covers temp-OOM?)

