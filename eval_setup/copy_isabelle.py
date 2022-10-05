import argparse
import shutil
import os
from tqdm import tqdm


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--isabelle", type=str, required=True)
    parser.add_argument("--isabelle-user", type=str, required=True)
    parser.add_argument("--number-of-copies", type=int, required=True)
    parser.add_argument("--output-path", type=str, required=True)
    args = parser.parse_args()

    for index in tqdm(range(args.number_of_copies)):
        index_path = os.path.join(args.output_path, f"isabelle_copy_{index}")
        if not os.path.exists(index_path):
            os.makedirs(index_path)

        main_isa_path = os.path.join(index_path, "main_isa")
        if not os.path.exists(main_isa_path):
            os.makedirs(main_isa_path)
        shutil.copytree(args.isabelle, main_isa_path)

        user_isa_path = os.path.join(index_path, "user_isa")
        if not os.path.exists(user_isa_path):
            os.makedirs(user_isa_path)
        shutil.copytree(args.isabelle_user, user_isa_path)

        # Edit the settings file such that the user home points to the right directory
        original_isabelle_home_user_string = "$USER_HOME/.isabelle"
        isabelle_home_user_string = str(user_isa_path)
        
        isabelle_versions = list(os.listdir(main_isa_path))
        assert len(isabelle_versions) == 1
        isabelle_identifier = isabelle_versions[0]
        isabelle_settings_path = os.path.join(main_isa_path, isabelle_identifier, "etc/settings")
        with open(isabelle_settings_path, "r") as f:
            settings = f.read()
        settings = settings.replace(original_isabelle_home_user_string, isabelle_home_user_string)
        with open(isabelle_settings_path, "w") as f:
            f.write(settings)
