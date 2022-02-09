import os
import json

from tqdm import tqdm

proof_and_state_dir = "/home/qj213/proof_and_state"
first_step_dir = "/home/qj213/first_step"


for file in os.listdir(proof_and_state_dir):
    with open(os.path.join(proof_and_state_dir, file)) as fhand, open(os.path.join(first_step_dir, file), "w") as fout:
        for line in tqdm(fhand.readlines()):
            line_json = json.loads(line.strip())
            source = line_json["source"]
            proof_step_string = source.split("<PS_SEP>")[0].strip()
            if "\\n" not in proof_step_string:
                # This is the first step
                fout.write(line.strip() + "\n")

