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
    proof_open = False
    last_state = ""
    for transition in transitions:
        if not transition:
            continue
        else:
            state, action, proof_level = transition.split("<\STATESEP>")
            hammer_results = "NA"
        state = state.strip()
        action = action.strip()
        proof_level = int(proof_level.strip())
        if action.startswith("lemma") or action.startswith("theorem"):
            problem_names.append(action)
            state_action_proof_level_tuples.append((state, action, proof_level, hammer_results))
            proof_open = True
        elif proof_open:
            state_action_proof_level_tuples.append((state, action, proof_level, hammer_results))

        if int(proof_level) == 0:
            proof_open = False
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

    # Figure out the parameters to start the server
    identity = mp.current_process()._identity
    if identity:
        rank = identity[0] % 200
    else:
        rank = 0
    port = 8000 + rank
    command = ["java", "-cp", jar_path, f"pisa.server.PisaOneStageServer{port}"]
    server_subprocess = subprocess.Popen(
        command,
        stdout=subprocess.PIPE, 
        stderr=subprocess.PIPE
    )
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
    json.dump(analysed_file, open(saving_path, "w"))

    # Clean up
    del env

    # Kill the server and its subprocesses
    try:
        p_process = psutil.Process(server_subprocess)
        children = p_process.children(recursive=True)
        for process in children:
            process.send_signal(signal.SIGTERM)
        p_process.send_signal(signal.SIGTERM)
    except psutil.NoSuchProcess:
        pass


if __name__ == "__main__":
    import glob
    import os
    from tqdm import tqdm

    jar_path = "/home/qj213/Portal-to-ISAbelle/target/scala-2.13/PISA-assembly-0.1.jar"
    isabelle_path = "/home/qj213/Isabelle2022"
    afp_path = "/home/qj213/afp-2022-12-06/thys"
    output_param_path = "/home/qj213/afp_extractions/params"
    output_data_path = "/home/qj213/afp_extractions/data"

    files = glob.glob(afp_path + '/**/*.thy', recursive=True)
    param_paths = list()

    for file_path in tqdm(files):
        identifier = file_path.replace("/", "_")

        working_directory = "/".join(file_path.split("/")[:6])
        params = {
            "jar_path": jar_path,
            "isabelle_path": isabelle_path,
            "working_directory": working_directory,
            "theory_file_path": file_path,
            "saving_path": f"{output_data_path}/{identifier}|output.json"
        }
        param_path = os.path.join(output_param_path, f"{identifier}.json")
        json.dump(params, open(param_path, "w"))

        param_paths.append(param_path)

    with mp.Pool(processes=int(mp.cpu_count()/4)) as pool:
        pool.map(extract_a_file, param_paths)
