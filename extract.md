## Extract PISA dataset
### Archive of formal proofs
Generate commands for extracting proofs.
Edit line 9 of command_generation/generate_commands_afp.py so that it uses your actually home directory.
Run the following command:
   ```shell
   python command_generation/generate_commands_afp.py
   ```
and follow the instructions. At the first step it asks you which ports you want to use. We current support 8000, 9000, 10000, 11000, 12000. You can also add your favourites by editing src/scala/server/PisaOneStageServers.scala. This dictates how many processes for extraction you wish to run at the same time.

It should say "A total of X files are due to be generated" with X > 5000.
And you should see files called afp_extract_script_${port_number}.sh in the directory.

To extract the files, run the following commands to install necessary libraries and execute the commands:
   ```shell
   pip install grpc
   pip install func_timeout
   bash afp_extract_script_${port_number_1}.sh &
   bash afp_extract_script_${port_number_2}.sh &
   bash afp_extract_script_${port_number_3}.sh &
   ...
   bash afp_extract_script_${port_number_n}.sh &
   ```

With a single process, the extraction takes ~5 days. This will extract files to the directory afp_extractions. We have also extracted this dataset, available for download at https://storage.googleapis.com/n2formal-public-data/afp_extractions.tar.gz.

To extract state-only source-to-target pairs, run the following command:
   ```shell
   python3 src/main/python/prepare_episodic_transitions.py -efd afp_extractions -sd data/state_only --state
   ```

To extract proof-only source-to-target pairs, run the following command:
   ```shell
   python3 src/main/python/prepare_episodic_transitions.py -efd afp_extractions -sd data/proof_only --proof
   ```

To extract proof-and-state source-to-target pairs, run the following command:
   ```shell
   python3 src/main/python/prepare_episodic_transitions.py -efd afp_extractions -sd data/proof_and_state --proof --state
   ```
Note that extraction involving proofs will take pretty long and will result in large files. State-only files amount to 8.1G.
