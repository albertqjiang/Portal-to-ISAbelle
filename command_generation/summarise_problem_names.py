import argparse
import os
import json


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description='Summarise the names and files from extractions.')
    parser.add_argument('--extraction-path', '-ep', help='The path to extraction files.')
    parser.add_argument('--dataset-name', '-dn', help='Dataset name after dumping.')
    parser.add_argument('--dump-path', '-dp', help='Path to dump the dataset.')
    args = parser.parse_args()

    dump_path = os.path.join(args.dump_path, args.dataset_name)
    if os.path.isdir(dump_path):
        os.rmdir(dump_path)

    os.mkdir(dump_path)

    total = 0
    for entry in os.listdir(args.extraction_path):
        entry_path = os.path.join(args.extraction_path, entry)
        for ground_truth_file in os.listdir(entry_path):
            if "ground_truth" in ground_truth_file:
                ground_truth_path = os.path.join(entry_path, ground_truth_file)
                ground_truth = json.load(open(ground_truth_path))
                file_name = ground_truth["file_name"]
                problem_names = ground_truth["problem_names"]
                assert len(problem_names) == 1
                theorem_name = problem_names[0]
                theorem_name = theorem_name.replace("\n", " ")
                theorem_name = " ".join(theorem_name.split())
                saving_name = file_name.split("/")[-1].strip(".thy")
                json.dump(
                    [
                        [
                            file_name, theorem_name
                        ]
                    ],
                    open(
                        os.path.join(dump_path, f"test_name_{total}.json"), "w"
                    )
                )
                total += 1