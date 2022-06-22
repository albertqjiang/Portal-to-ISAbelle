from PisaFlexibleClient import initialise_env
import os
import pickle


def match_names_single_file_to_data_play_szymon(
        port, working_directory, isa_path, theory_file_path, out_dir, error_log_dir):
    env = initialise_env(
        port=port,
        working_directory=working_directory,
        isa_path=isa_path,
        theory_file_path=theory_file_path
    )
    try:
        output_string = env.post("<get global facts from file>")
        list_of_string_tuples = output_string.split("<SEP>")
        global_fact_dict = {}
        for element in list_of_string_tuples:
            name, definition = element.split("<DEF>")
            global_fact_dict[name.strip()] = definition.strip()
        pickle.dump(global_fact_dict, open(os.path.join(out_dir, f"dict_{theory_file_path}"), "wb"))
    except Exception as e:
        with open(os.path.join(error_log_dir, f"error_log_{theory_file_path}.txt"), "w") as fout:
            fout.write(e)
