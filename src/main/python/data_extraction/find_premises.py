import json
import multiprocessing as mp

from utils.pisa_server_control import start_server, close_server
from pisa_client import initialise_env

def find_premises_from_a_file(path_dict):
    problems_path = path_dict["problems_path"]
    jar_path = path_dict["jar_path"]
    saving_directory = path_dict["saving_directory"]

    saving_path = os.path.join(
        saving_directory, 
        os.path.basename(problems_path).replace("thy_problems.json", "thy_premises.jsonl")
    )
    error_path = saving_path.replace("thy_premises.jsonl", "thy_errors.txt")

    problems_json = json.load(open(problems_path))
    theory_file_path = problems_json["theory_file_path"]
    working_directory = problems_json["working_directory"]
    problems = problems_json["problems"]

    env = None
    server_subprocess_id = None
    try:
        # Figure out the parameters to start the server
        identity = mp.current_process()._identity
        if identity:
            rank = identity[0] % 200
        else:
            rank = 0
        port = 8000 + rank
        server_subprocess_id = start_server(jar_path, port)
        
        # Getting the environment
        env = initialise_env(
            port=port,
            isa_path=isabelle_path,
            theory_file_path=theory_file_path,
            working_directory=working_directory,
        )
        entire_thy_concatenated = env.post("<parse entire thy>")
        entire_thy_concatenated = entire_thy_concatenated.split("<SEP>")
        entire_thy_concatenated = [line.strip() for line in entire_thy_concatenated]

        # Find the index where the last "end" is
        for i in range(len(entire_thy_concatenated)-1, -1, -1):
            if entire_thy_concatenated[i] == "end":
                break

        if i == 0:
            raise Exception(f"The file is empty:\n{' '.join(entire_thy_concatenated)}")

        steps_before_last_end = entire_thy_concatenated[:i]
        steps_before_last_end = ' '.join(steps_before_last_end)
        env.initialise()
        print()
        env.step_to_top_level_state(steps_before_last_end, "default", "default")

        # Find the premises to each problem
        premises = []
        for problem in problems:
            try:
                problem_name = problem["problem_name"]
                only_name = problem_name.strip()
                print("What")
                assert only_name.startswith("lemma") or only_name.startswith("theorem"), only_name
                only_name = only_name.lstrip("lemma").lstrip("theorem").strip()
                only_name = only_name.split(":")[0].strip()
                only_name = only_name.split()[0].strip()
                only_name = only_name.split("[")[0].strip()

                full_proof_text = problem["full_proof_text"]
                split = problem["split"]
                print("Here")
                premises_and_their_definitions = env.get_premises_and_their_definitions("default", only_name, full_proof_text)
                print("There")
                premises.append(
                    {   
                        "theory_file_path": theory_file_path,
                        "working_directory": working_directory,
                        "problem_name": problem_name,
                        "only_name": only_name,
                        "premises_and_their_definitions": premises_and_their_definitions,
                        "full_proof_text": full_proof_text,
                        "split": split,
                    }
                )
            except Exception as excp:
                print(excp)
                continue
        
        with open(saving_path, "w") as fout:
            for premise in premises:
                fout.write(json.dumps(premise) + "\n")

    except Exception as e:
        print(e)
        with open(error_path, "w") as fout:
            fout.write(e)

    # Clean up
    del env
    if server_subprocess_id is not None:
        close_server(server_subprocess_id)

   
if __name__ == "__main__":
    import argparse
    import glob
    import os
    import shutil
    parser = argparse.ArgumentParser(description='Extracting translation pairs.')
    parser.add_argument('--extraction-file-directory', '-efd', help='Where the parsed json files are')
    parser.add_argument('--saving-directory', '-sd', help='Where to save the translation pairs')
    parser.add_argument('--jar-path', '-jp', help='Path to the jar file', 
        default="/home/qj213/Portal-to-ISAbelle/target/scala-2.13/PISA-assembly-0.1.jar")
    parser.add_argument('--isabelle-path', '-ip', help='Path to the Isabelle installation', 
        default="/home/qj213/Isabelle2022")
    args = parser.parse_args()

    extraction_file_directory = args.extraction_file_directory
    saving_directory = args.saving_directory
    jar_path = args.jar_path
    isabelle_path = args.isabelle_path
    if os.path.isdir(saving_directory):
        shutil.rmtree(saving_directory)
    os.makedirs(saving_directory)

    # files = glob.glob(f"{extraction_file_directory}/**/*.thy_problems.json", recursive=True)
    # list_of_path_dicts = [
    #     {"problems_path": file, "saving_directory": saving_directory, "jar_path": jar_path, "isabelle_path": isabelle_path}
    #     for file in files
    # ]
    
    # with mp.Pool(processes=int(mp.cpu_count()/10)) as pool:
    #     pool.map(find_premises_from_a_file, list_of_path_dicts)
    find_premises_from_a_file(
        {
            "problems_path": "/home/qj213/problems/afp/_home_qj213_afp-2022-12-06_thys_pGCL_WellDefined.thy_problems.json", 
            "saving_directory": saving_directory, 
            "jar_path": jar_path, 
            "isabelle_path": isabelle_path
        }
    )
    