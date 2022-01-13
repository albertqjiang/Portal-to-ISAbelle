import json
import random
import shutil

from tqdm import tqdm
from mpmath import mp, mpf, fmod
import hashlib
import math


random.seed(0)
mp.dps = 50


def split_transitions(problem_names, transitions):
    transitions_for_problems = {problem_name: [] for problem_name in problem_names}
    current_problem_name = ""
    for transition in transitions:
        if transition[1] in problem_names:
            current_problem_name = transition[1]
        elif "proof" not in transition[0]:
            continue
        transitions_for_problems[current_problem_name].append(transition)
    return transitions_for_problems


def extract_siblings(proof_steps, current_step_index):
    sibling_indices = []
    current_proof_level = proof_steps[current_step_index][2]
    # We shall have current_proof_level ≥ 1 since we're inside a proof
    search_index = current_step_index - 1
    while search_index >= 0:
        if proof_steps[search_index][2] > current_proof_level:
            # Unimportant proof subtree content*
            pass
        elif proof_steps[search_index][2] == current_proof_level:
            # Sibling
            sibling_indices.insert(0, search_index)
        elif (proof_steps[search_index][2] < current_proof_level):
            # Higher level steps
            return sibling_indices, search_index
        search_index -= 1
    return [], None

def extract_needed(proof_steps, current_step_index, needed_found):
    if needed_found[current_step_index]:
        return needed_found[current_step_index]
    sibling_indices, search_index = extract_siblings(proof_steps, current_step_index)
    if search_index > 0:
        return extract_needed(proof_steps, search_index, needed_found) + [search_index] + sibling_indices
    elif search_index == 0:
        return [search_index] + sibling_indices
    else:
        raise AssertionError


def process_translations_for_a_problem(transitions_for_a_problem, proof=False, state=False, needed=False):
    """Transform the transitions for a problem to translation pairs"""
    # The first one is the lemma/theorem definition
    previous_proof_segment = transitions_for_a_problem[0][1]
    needed_found = {i: False for i in range(len(transitions_for_a_problem))}

    translation_pairs = []
    for i in range(1, len(transitions_for_a_problem)):
        transition = transitions_for_a_problem[i]
        translation_src = ""
        if needed:
            needed_indices = extract_needed(transitions_for_a_problem, i, needed_found)
            needed_found[i] = needed_indices
            needed_segment = " \\n ".join([transitions_for_a_problem[index][1] for index in needed_indices])
            translation_src += f"Needed: {needed_segment} State: {transition[0]}"
        else:
            if proof:
                translation_src += "Proof: {}".format(previous_proof_segment)
            if proof and state:
                translation_src += " <PS_SEP> "
            if state:
                translation_src += "State: {}".format(transition[0])

        translation_pairs.append((translation_src, transition[1]))
        previous_proof_segment += " \\n " + transition[1]
    return translation_pairs


def trim_string(s: str):
    """Remove all change line characters and replace them with spaces"""
    return " ".join(s.replace("\n", " ").split())


def hash_string_to_int(arg):
    return int(hashlib.sha256(arg.encode("utf-8")).hexdigest(), 16) % 10**30


def hash_string_to_float(arg):
    x = mpf(hash_string_to_int(arg))
    return fmod(x * mp.pi, mpf(1.))


def get_split(arg):
    float_hash = hash_string_to_float(arg)
    if float_hash < 0.95:
        return "train"
    elif float_hash < 0.96:
        return "val"
    else:
        return "test"


def random_split_file_names(file_names, val_test_files=100):
    random.shuffle(file_names)
    return file_names[:-2 * val_test_files], file_names[-2 * val_test_files:-val_test_files], \
        file_names[-val_test_files:]


def process_files_with_proof_statements(file_names, saving_directory, proof=False, state=False, needed=False):
    problem_names_split = {
        "train": list(),
        "val": list(),
        "test": list()
    }
    for file_path in tqdm(file_names):
        file = json.load(open(file_path))
        original_file_name = file['file_name']
        problem_names = set(file['problem_names'])
        transitions_split = split_transitions(problem_names, file['translations'])

        sources = {
            "train": list(),
            "val": list(),
            "test": list()
        }
        targets = {
            "train": list(),
            "val": list(),
            "test": list()
        }
        for problem_name in set(file['problem_names']):
            split = get_split(problem_name)
            problem_names_split[split].append((original_file_name, problem_name))
            translation_pairs = process_translations_for_a_problem(transitions_split[problem_name],
                                                                   proof=proof, state=state, needed=needed)
            for x, y in translation_pairs:
                sources[split].append(trim_string(x))
                targets[split].append(trim_string(y))

        for key in sources:
            with open(os.path.join(saving_directory, "{}.src".format(key)), "a") as fout:
                for x in sources[key]:
                    fout.write(x + "\n")
            with open(os.path.join(saving_directory, "{}.tgt".format(key)), "a") as fout:
                for y in targets[key]:
                    fout.write(y + "\n")
    json.dump(problem_names_split, open(os.path.join(saving_directory, "problem_names_split.json"), "w"))


if __name__ == "__main__":
    import argparse
    import glob
    import os
    parser = argparse.ArgumentParser(description='Extracting translation pairs.')
    parser.add_argument('--extraction-file-directory', '-efd', help='Where the parsed json files are')
    parser.add_argument('--saving-directory', '-sd', help='Where to save the translation pairs')
    parser.add_argument('--proof', dest='proof', action='store_true')
    parser.add_argument('--state', dest='state', action='store_true')
    parser.add_argument('--needed', dest="needed", action='store_true')
    args = parser.parse_args()

    assert args.proof or args.state or args.needed
    if args.needed:
        proof_state_suffix = "needed"
    elif args.proof and not args.state:
        proof_state_suffix = "proof"
    elif args.state and not args.proof:
        proof_state_suffix = "state"
    else:
        proof_state_suffix = "proof_and_state"

    saving_directory = "{}_with_{}".format(args.saving_directory, proof_state_suffix)
    if os.path.isdir(saving_directory):
        shutil.rmtree(saving_directory)
    os.makedirs(saving_directory)

    file_names = list(glob.glob("{}/*/*_ground_truth.json".format(
        args.extraction_file_directory)))
    process_files_with_proof_statements(file_names, saving_directory, proof=args.proof, state=args.state, needed=args.needed)
