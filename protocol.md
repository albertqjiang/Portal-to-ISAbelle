# The PISA protocol

PISA supports communication over [gRPC](https://grpc.io/). Once the server is hosted, it can be communicated with the following protocol:

(Words starting with $ are not literal syntax, but variables)

- Parse the current file.
  - **Input:** <code>"PISA extract data"</code>
  - **Output:** The parsed file in the following format
  
  <code>"${STATE_STRING_1}<\STATESEP>${STEP_STRING_1}<\STATESEP>${PROOF_LEVEL_1}<\TRANSEP>..."</code>
  
- Parse a certain piece of proof text.
  - **Input:** <code>"\<parse text\> $text"</code>
  - **Output:** The parsed text in the following format
  
  <code>"${STEP_STRING_1}\<SEP\>${STEP_STRING_2}\<SEP\>${STEP_STRING_3}\<SEP\>..."</code>
    
- Get all the possible definitions of the theorem with the given name, for the top level state "default" (requires initialisation).
  - **Input:** <code>"\<get all definitions\> ${theorem_name}"</code>
  - **Output:** Every single line is a possible definition of the theorem
  
