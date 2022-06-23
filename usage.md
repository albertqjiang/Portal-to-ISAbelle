## Usage
The common usage of PISA is to have a scala server communicating with Isabelle.
Then you can use whatever client you like to talk to the server through the [gRPC protocol](https://grpc.io).

To give it a try, run the following command to start the gRPC server at port 8000:
```console
sbt "runMain pisa.server.PisaOneStageServer8000"
```

Then run the following Python command to talk to the server in a REPL:
```console
python3 src/main/python/cmd_client.py
```

## Protocol
Through a language protocol, you can communicate what you want to do with the server.
Here we detail the protocol.

What you put through in the gRPC message.
- ``PISA extract data`` This extracts the data from the current file, including, for every transition,
the proof state, the proof level, and the next proof step.
- ``PISA extract data with hammer`` (*Experimental*) This extracts the data from the current file
much like the message above, but with the extra information of whether sledgehammer can be applied
for each transition.
- ``<initialise>`` Initialise the environment such that there will be a state with the key "default".
- ``<get all definitions> $THEOREM_STRING`` Returns all the definitional constants used in the
$THEOREM_STRING. Note that the environment must be initialised first and the $THEOREM_STRING
must be something that can be declared currently in the environment.
- ``<local facts and defs> $TLS_NAME`` Returns the local facts and definitions for the top-level-state
with the name $TLS_NAME.
- ``<global facts and defs> $TLS_NAME`` Returns the global facts and definitions for the top-level-state
with the name $TLS_NAME.
- ``<total facts and defs> $TLS_NAME`` Returns the total facts and definitions for the top-level-state
with the name $TLS_NAME. This includes both local and global facts and definitions.
- ``<get global facts from file>`` Returns the global facts available at the end of the current file.
- ``<list states>``