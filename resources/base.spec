This base.spec file covers some shared, base types and concepts.

stateMachine

lvar
- vectorClock
- revisionTree
- transactionProposal

hash

range

relation
scratch: relation

boom
  {join [x y z]
   where (> x.b y.b)
   select [x.a z.a z.b]
   into [w w2 w3]}

queue

checksum

internals
- queue
-- send queue
-- recv queue
-- write queue
-- read queue
- snapshotable
-- iterator
-- storage
-- config

  base:
    - uuid (readOnly)
    - ver  (uint64IncrementOnly)
    - name (readOnly, uniqueInParentContainer)

  name: string [a-zA-Z0-9][a-zA-Z0-9_-]*

  uuid: nonEmptyUUID
