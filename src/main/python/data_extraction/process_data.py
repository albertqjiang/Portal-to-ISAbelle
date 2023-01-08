import json
from tqdm import tqdm
from mpmath import mp, mpf, fmod
import hashlib
import os


def hash_string_to_int(arg):
    return int(hashlib.sha256(arg.encode("utf-8")).hexdigest(), 16) % 10**30

def hash_string_to_float(arg):
    x = mpf(hash_string_to_int(arg))
    return fmod(x * mp.pi, mpf(1.))

def get_split(arg):
    float_hash = hash_string_to_float(arg)
    if float_hash < 0.97:
        return "train"
    elif float_hash < 0.98:
        return "val"
    else:
        return "test"

def process_one_extraction_file(file):
    # Load the file
    extraction = json.load(open(file))
    # Get the information
    theory_file_path = extraction["theory_file_path"]
    assert os.path.exists(theory_file_path), f"Could not find {theory_file_path}"
    working_directory = extraction["working_directory"]
    problem_names = extraction["problem_names"]
    transitions = extraction["translations"]
    # Leave in only the actual problem names
    problem_names = [problem_name.strip() for problem_name in problem_names]
    problem_names = [problem_name for problem_name in problem_names if (problem_name.startswith("lemma") or problem_name.startswith("theorem")) and not problem_name.startswith("lemmas")]
    
    if not problem_names:
        return {
            "theory_file_path": theory_file_path,
            "working_directory": working_directory,
            "problems": []
        }
    # Filter out comments
    good_transitions = []
    for transition in transitions:
        transition_text = transition[1].strip()
        if transition_text.startswith("(*") and transition_text.endswith("*)"):
            continue
        if (transition_text.startswith("text \\<open>") or transition_text.startswith("txt \\<open>")) and transition_text.endswith("\\<close>"):
            continue
        good_transitions.append(transition)
        
    # Filter out all the transitions that are not in proofs
    current_problem_name = None
    problem_name_to_transitions = {}
    proof_open = False
    for transition in good_transitions:
        _, transition_text, proof_level, _ = transition
        # print(transition_text, proof_level)
        if transition_text in problem_names:
            current_problem_name = transition_text
            assert proof_level == 0, transition
            problem_name_to_transitions[current_problem_name] = [transition]
            proof_open = True
        elif proof_level == 0:
            proof_open = False
            continue
        elif proof_open:
            problem_name_to_transitions[current_problem_name].append(transition)
        else:
            pass

    assert None not in problem_name_to_transitions
    assert set(problem_name_to_transitions.keys()) == set(problem_names)

    problems = []
    for problem_name in problem_names:
        transitions = problem_name_to_transitions[problem_name]
        full_proof_text = "\n".join([transition[1] for transition in transitions])
        split = get_split(problem_name)
        problems.append(
            {
                "problem_name": problem_name,
                "full_proof_text": full_proof_text,
                "transitions": transitions,
                "split": split
            }
        )
        
    return {
        "theory_file_path": theory_file_path,
        "working_directory": working_directory,
        "problems": problems
    }

def process_extractions(files, saving_directory):
    """Process the extractions"""
    for file in tqdm(files):
        extraction_from_a_file = process_one_extraction_file(file)
        basename = os.path.basename(file)
        assert basename.endswith("thy_output.json"), basename

        new_basename = basename.replace("thy_output.json", "thy_problems.json")
        saving_file_path = os.path.join(saving_directory, new_basename)
        json.dump(extraction_from_a_file, open(saving_file_path, "w"))
        

if __name__ == "__main__":
    import argparse
    import glob
    import os
    import shutil
    parser = argparse.ArgumentParser(description='Processing and filtering proving data.')
    parser.add_argument('--extraction-file-directory', '-efd', help='Where the parsed json files are')
    parser.add_argument('--saving-directory', '-sd', help='Where to save the translation pairs')
    args = parser.parse_args()

    extraction_file_directory = args.extraction_file_directory
    saving_directory = args.saving_directory
    if os.path.isdir(saving_directory):
        shutil.rmtree(saving_directory)
    os.makedirs(saving_directory)

    files = glob.glob(f"{extraction_file_directory}/**/*.thy_output.json", recursive=True)
    process_extractions(files, saving_directory)
    # json.dump(
    #     process_one_extraction_file("/home/qj213/afp_extractions/data/_home_qj213_afp-2022-12-06_thys_pGCL_Tutorial_Primitives.thy_output.json"),
    #     open("test.json", "w"),
    # )