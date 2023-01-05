import json
import random
import shutil
from tqdm import tqdm
from mpmath import mp, mpf, fmod
import hashlib
import math
import os


def process_one_extraction_file(file):
    # Load the file
    extraction = json.load(open(file))
    # Get the information
    theory_file_path = extraction["theory_file_path"]
    assert os.file.exists(theory_file_path), f"Could not find {theory_file_path}"
    working_directory = extraction["working_directory"]
    problem_names = extraction["problem_names"]
    transitions = extraction["translations"]
    # Leave in only the actual problem names
    problem_names = [problem_name for problem_name in problem_names if problem_name.startswith("lemma")]

    # Filter out comments
    good_transitions = []
    for transition in transitions:
        transition_text = transition[1].strip()
        if transition_text.startswith("(*") and transition_text.endswith("*)"):
            continue
        if transition_text.startswith("text \\\\<open>") and transition_text.endswith("\\\\<close>"):
            continue
        good_transitions.append(transition)
        
    # Filter out all the transitions that are not in proofs
    current_problem_name = None
    problem_name_to_transitions = {}
    for transition in good_transitions:
        _, transition_text, proof_level, _ = transition
        if transition_text in problem_names:
            current_problem_name = transition_text
            assert proof_level == 0, transition
            problem_name_to_transitions[current_problem_name] = [transition]
        else:
            problem_name_to_transitions[current_problem_name].append(transition)
    assert None not in problem_name_to_transitions
    assert set(problem_name_to_transitions.keys()) == set(problem_names)

    problems = []
    for key, value in problem_name_to_transitions.items():
        full_proof_text = " ".join([transition[1] for transition in value])
        problems.append(
            {
                "problem_name": key,
                "full_proof_text": full_proof_text,
                "transitions": value
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
        extraction = process_one_extraction_file(file)

        
if __name__ == "__main__":
    # import argparse
    # import glob
    # import os
    # parser = argparse.ArgumentParser(description='Extracting translation pairs.')
    # parser.add_argument('--extraction-file-directory', '-efd', help='Where the parsed json files are')
    # parser.add_argument('--saving-directory', '-sd', help='Where to save the translation pairs')
    # args = parser.parse_args()

    # extraction_file_directory = args.extraction_file_directory
    # saving_directory = args.saving_directory
    # if os.path.isdir(saving_directory):
    #     shutil.rmtree(saving_directory)
    # os.makedirs(saving_directory)

    # files = glob.glob(f"{extraction_file_directory}/**/*.thy_output.json", recursive=True)
    # process_extractions(files, saving_directory)
    print(process_one_extraction_file("/home/qj213/afp_extractions/data/_home_qj213_afp-2022-12-06_thys_pGCL_Tutorial_Primitives.thy_output.json"))