import json
import multiprocessing as mp

from utils.pisa_server_control import start_server, close_server
from pisa_client import initialise_env

def find_premises_from_a_file(path_dict):
    problems_path = path_dict["problems_path"]
    jar_path = path_dict["jar_path"]
    saving_directory = path_dict["saving_directory"]
    server_dump_path = path_dict["server_dump_path"]

    saving_path = os.path.join(
        saving_directory, 
        os.path.basename(problems_path).replace("thy_problems.json", "thy_premises.jsonl")
    )
    error_path = saving_path.replace("thy_premises.jsonl", "thy_errors.txt")

    if server_dump_path is None:
        server_output_path, server_error_path = None, None
    else:
        server_output_path = os.path.join(
            server_dump_path,
            os.path.basename(problems_path).replace("thy_problems.json", "thy_server_output.txt")
        )
        server_error_path = os.path.join(
            server_dump_path,
            os.path.basename(problems_path).replace("thy_problems.json", "thy_server_error.txt")
        )

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

        server_subprocess_id = start_server(
            jar_path, port, outputfile=server_output_path, errorfile=server_error_path
        )
        
        # Getting the environment
        print(f"Port: {port}")
        env = initialise_env(
            port=port,
            isa_path=isabelle_path,
            theory_file_path=theory_file_path,
            working_directory=working_directory,
        )

        # Find the premises to each problem
        premises = []
        for problem in problems:
            try:
                problem_name = problem["problem_name"].strip()
                
                assert problem_name.startswith("lemma") or problem_name.startswith("theorem"), problem_name
                only_name = problem_name.lstrip("lemma").lstrip("theorem").strip()
                only_name = only_name.split(":")[0].strip()
                only_name = only_name.split()[0].strip()
                only_name = only_name.split("[")[0].strip()

                if not only_name: continue

                full_proof_text = problem["full_proof_text"]
                split = problem["split"]
                # print(f"Problem name: {problem_name}. Only name: {only_name}.")
                premises_and_their_definitions = env.get_premises_and_their_definitions(
                    problem_name, only_name, full_proof_text
                )
                # print(premises_and_their_definitions)
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
        # print(saving_path)
        with open(saving_path, "w") as fout:
            for premise in premises:
                fout.write(json.dumps(premise) + "\n")

    except Exception as e:
        print(e)
        with open(error_path, "w") as fout:
            fout.write(str(e))

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
    parser.add_argument('--server-dump-path', '-sdp', help="Where to dump the server's output")
    parser.add_argument('--jar-path', '-jp', help='Path to the jar file', 
        default="/home/qj213/Portal-to-ISAbelle/target/scala-2.13/PISA-assembly-0.1.jar")
    parser.add_argument('--isabelle-path', '-ip', help='Path to the Isabelle installation', 
        default="/home/qj213/Isabelle2022")
    args = parser.parse_args()

    extraction_file_directory = args.extraction_file_directory
    saving_directory = args.saving_directory
    jar_path = args.jar_path
    isabelle_path = args.isabelle_path
    server_dump_path = args.server_dump_path

    if not os.path.exists(saving_directory):
        os.makedirs(saving_directory)

    files = glob.glob(f"{extraction_file_directory}/**/*.thy_problems.json", recursive=True)
    list_of_path_dicts = [
        {
            "problems_path": file, 
            "saving_directory": saving_directory, 
            "jar_path": jar_path, 
            "isabelle_path": isabelle_path,
            "server_dump_path": server_dump_path,
        }
        for file in files
    ]

    print(list_of_path_dicts[1])
    
    # with mp.Pool(processes=int(mp.cpu_count()/10)) as pool:
    with mp.Pool(processes=1) as pool:
        pool.map(find_premises_from_a_file, [list_of_path_dicts[1]])
    # find_premises_from_a_file(
    #     {
    #         "problems_path": "/home/qj213/problems/afp/_home_qj213_afp-2022-12-06_thys_Formal_SSA_Construct_SSA.thy_problems.json", 
    #         "saving_directory": saving_directory, 
    #         "jar_path": jar_path, 
    #         "isabelle_path": isabelle_path,
    #         "server_dump_path": server_dump_path,
    #     }
    # )
