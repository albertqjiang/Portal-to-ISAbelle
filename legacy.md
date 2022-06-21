# Untested legacy stuff
**The following content was built on the 2020 version of Isabelle with afp-2021-02-11. They have not been tested with Isabelle2021 and might contain bugs.**
## Running proof search
After the heap images have been built, experiments of proof searching can be run.
1. Configure the Isabelle binary path and the AFP path

   Go to PisaSearch.scala, change the second string of line 352 so that it points to your afp path.

   Change the string in line 383 so that it points to the directory where Isabelle was installed.

   (For the last two steps, be careful because the substitution is based on strings and quite subtle. Make sure everything checks out.)

   Lines 46-79 contain the querying commands. Change these to use OpenAI's internal API.

2. Get the universal test theorem names

   ```shell
   cd Portal-to-ISAbelle
   wget http://www.cs.toronto.edu/~ajiang/universal_test_theorems.tar.gz
   tar -xzvf universal_test_theorems.tar.gz
   ```
3. Generate the proof search scripts

   ```shell
   mkdir results
   python command_generation/search_command_generator.py
   ```
   Follow the instructions.

4. Run the proof search experiments

   In scripts, some files have been generated in the format of
   eval_search_conj_{boolean}_use_proof_{boolean}_use_state_first_{boolean}_{$script_number}.sh

   Wrap them with Python to use subprocesses.

   The results will be in the results directory.


### Python packages
grpc

It might work with lower versions but they have not been tested.

## Usage
<!-- ### Build AFP heap images
First you should know the path to the Isabelle binary executable. 
On MacOS, with Isabelle2020, the path to it is
```shell
/Applications/Isabelle2020.app/Isabelle/bin/isabelle
```
On linux, it might be
```shell
~/Isabelle2020/bin/isabelle
```

I will alias this to isabelle for convenience:
```shell
alias isabelle="PATH TO THE EXECUTABLE"
```

Download the [Archive of Formal Proofs](https://www.isa-afp.org/download.html).
We use the version afp-2021-02-11 for data extraction, but a later version is also fine.
Let's say the path to this is AFP_PATH. Build the afp entries:
```shell
isabelle build -b -D $AFP_PATH/thys
```
This will take ~12 hours with an 8-core CPU. 
You should check that in the process, heaps are built for each afp project in the directory
```shell
~/.isabelle/Isabelle2020/heaps/polyml-5.8.1_x86_64_32-darwin
```
(The exact path might differ if you have different OS or polyml verions but should be easy to find) -->


### Model evaluation
See src/main/python/load_problem_by_file_and_name.py for an example of using an oracle theorem prover
to evaluate on some problems.

Notice in line 101, the theory file path is altered.
This is because the afp extraction and evaluation happened on different machines.
Comment this line out if you manually extracted the afp files, or swap
```shell
/Users/qj213/Projects/afp-2021-02-11
```
for the location of afp files on your computer.

When doing evaluation, in one terminal, run
```shell
sbt "runMain pisa.server.PisaOneStageServer9000"
```
You can switch to port 8000, 10000, 11000, or 12000. 9000 is the default used in the Python file.
In another terminal, use Python function evaluate_problem to obtain a proof success or failure.

You will need to pass in a model as an argument that has the method predict.
model.predict takes in a string of proof state, and return the next proof transition.

The evaluate_problem method executes prediction for a maximum of 100 steps by default.

Problem evaluation currently only allows agents based on proof states only.
Agents based on previous proof segments and hybrid-input agents will be supported in the near future.
