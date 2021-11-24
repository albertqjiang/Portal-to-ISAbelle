from PisaFlexibleClient import IsaFlexEnv

import os


if __name__ == "__main__":
    isa_path = input("Path to Isabelle (default /home/qj213/Isabelle2021): ")
    isa_path = "/home/qj213/Isabelle2021" if isa_path is None else isa_path
    afp_path = input("Path to AFP (default /home/qj213/afp-2021-10-22): ")
    afp_path = "/home/qj213/afp-2021-10-22" if afp_path is None else afp_path
    file_path = input("Path to an AFP file (default /home/qj213/afp-2021-10-22/FunWithFunctions/FunWithFunctions.thy): ")
    file_path = "/home/qj213/afp-2021-10-22/FunWithFunctions/FunWithFunctions.thy" if file_path is None else file_path
    env = IsaFlexEnv(
        port=8000, isa_path=isa_path, starter_string="theory Test imports Complex_Main begin",
        working_directory=os.path.join(afp_path, "thys", "FunWithFunctions")     
    )

    while True:
        proof_step = input("Your chosen proof step ('<fin>' to exit): ")
        if proof_step.strip().startswith("<fin>"):
            break
        obs, rewards, done, _ = env.step(proof_step)
        print(obs)
