import psutil
import signal
import json
import multiprocessing as mp
import subprocess
import time

from pisa_client import initialise_env


def analyse_file_string(whole_file_string):
    transitions = whole_file_string.split("<\TRANSEP>")
    state_action_proof_level_tuples = list()
    problem_names = list()
    # proof_open = False
    # last_proof_level = 0
    for transition in transitions:
        if not transition:
            continue
        else:
            state, action, proof_level = transition.split("<\STATESEP>")
            hammer_results = "NA"
        state = state.strip()
        action = action.strip()
        proof_level = int(proof_level.strip())
        if (action.startswith("lemma") or action.startswith("theorem")) and not action.startswith("lemmas"):
            problem_names.append(action)
        #     state_action_proof_level_tuples.append((state, action, proof_level, hammer_results))
        #     proof_open = True
        # elif proof_open:
        #     state_action_proof_level_tuples.append((state, action, proof_level, hammer_results))

        # if last_proof_level > 0 and proof_level == 0:
        #     proof_open = False
        # last_proof_level = proof_level
        state_action_proof_level_tuples.append((state, action, proof_level, hammer_results))
    return {
        "problem_names": problem_names,
        "translations": state_action_proof_level_tuples
    }


def extract_a_file(params_path):
    """
    Extracts the data from a single file.

    :param params_path: Path to the JSON file containing the parameters.
    :param rank: Rank of the process in the subprocess pool.
    :return: None
    """
    # Load the parameters
    params = json.load(open(params_path))
    jar_path = params["jar_path"]
    isabelle_path = params["isabelle_path"]
    working_directory = params["working_directory"]
    theory_file_path = params["theory_file_path"]
    saving_path = params["saving_path"]
    error_path = params["error_path"]

    env = None
    try:
        # Figure out the parameters to start the server
        identity = mp.current_process()._identity
        if identity:
            rank = identity[0] % 200
        else:
            rank = 0
        port = 8000 + rank
        command = ["java", "-cp", jar_path, f"pisa.server.PisaOneStageServer{port}"]
        server_subprocess_id = subprocess.Popen(
            command,
            stdout=subprocess.PIPE, 
            stderr=subprocess.PIPE
        ).pid
        time.sleep(5)

        # Getting the environment
        env = initialise_env(
            port=port,
            isa_path=isabelle_path,
            theory_file_path=theory_file_path,
            working_directory=working_directory,
        )
        whole_file_string = env.post("PISA extract data")
        # Parse the string and dump
        analysed_file = analyse_file_string(whole_file_string)
        analysed_file["theory_file_path"] = theory_file_path
        analysed_file["working_directory"] = working_directory
        json.dump(analysed_file, open(saving_path, "w"))
    except Exception as e:
        print(e)
        json.dump({"error": str(e)}, open(error_path, "w"))

    # Clean up
    del env
    
    # Kill the server and its subprocesses
    try:
        p_process = psutil.Process(server_subprocess_id)
        children = p_process.children(recursive=True)
        for process in children:
            process.send_signal(signal.SIGTERM)
        p_process.send_signal(signal.SIGTERM)
    except psutil.NoSuchProcess:
        pass


if __name__ == "__main__":
    import glob
    import os
    import argparse
    import shutil
    from tqdm import tqdm
    parser = argparse.ArgumentParser(description='Extracting transition data from theory files.')
    parser.add_argument('--jar-path', '-jp', help='Path to the jar file', default=None)
    parser.add_argument('--isabelle-path', '-ip', help='Path to the Isabelle installation', default=None)
    parser.add_argument('--extraction-file-directory', '-efd', help='Where the parsed json files are')
    parser.add_argument('--saving-directory', '-sd', help='Where to save the translation pairs')
    args = parser.parse_args()

    jar_path = args.jar_path
    if jar_path is None:
        jar_path = "/home/qj213/Portal-to-ISAbelle/target/scala-2.13/PISA-assembly-0.1.jar"
    isabelle_path = args.isabelle_path
    if isabelle_path is None:
        isabelle_path = "/home/qj213/Isabelle2022"
    extraction_file_directory = args.extraction_file_directory
    saving_directory = args.saving_directory
    if not os.path.isdir(saving_directory):
        os.makedirs(saving_directory)

    output_data_path = os.path.join(saving_directory, "data")
    output_param_path = os.path.join(saving_directory, "params")
    if not os.path.isdir(output_data_path):
        os.makedirs(output_data_path)
    if not os.path.isdir(output_param_path):
        os.makedirs(output_param_path)

    # extraction_file_directory = "/home/qj213/afp-2022-12-06/thys"
    # extraction_file_directory = "/home/qj213/Isabelle2022/src/HOL"
    # saving_directory = "/home/qj213/afp_extractions"
    # saving_directory = "/home/qj213/std_extractions"

    files = glob.glob(extraction_file_directory.rstrip("/") + '/**/*.thy', recursive=True)
    param_paths = list()

    for file_path in tqdm(files):
        identifier = file_path.replace("/", "_")

        if "thys" in file_path:
            bits = file_path.split("/")
            thys_index = bits.index("thys")
            working_directory = "/".join(bits[:thys_index + 2])
        elif "src/HOL" in file_path:
            bits = file_path.split("/")
            hol_index = bits.index("HOL")
            bits = bits[:-1]
            bits = bits[:hol_index + 2]
            working_directory = "/".join(bits)
        else:
            raise AssertionError
        saving_path = f"{output_data_path}/{identifier}_output.json"
        error_path = f"{output_data_path}/{identifier}_error.json"
        if os.path.exists(saving_path) or os.path.exists(error_path):
            continue
        params = {
            "jar_path": jar_path,
            "isabelle_path": isabelle_path,
            "working_directory": working_directory,
            "theory_file_path": file_path,
            "saving_path": saving_path,
            "error_path": error_path,
        }
        param_path = os.path.join(output_param_path, f"{identifier}.json")
        json.dump(params, open(param_path, "w"))

        param_paths.append(param_path)

    with mp.Pool(processes=int(mp.cpu_count()/10)) as pool:
        pool.map(extract_a_file, param_paths)
