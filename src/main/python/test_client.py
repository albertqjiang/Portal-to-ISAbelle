from PisaFlexibleClient import IsaFlexEnv

if __name__ == "__main__":
    isa_path = "/home/qj213/Isabelle2021"
    file_path = "/home/qj213/afp-2021-10-22/thys/FunWithFunctions/FunWithFunctions.thy"
    working_directory = "/home/qj213/afp-2021-10-22/thys/FunWithFunctions"
    env = IsaFlexEnv(
        port=8000, isa_path=isa_path, starter_string=file_path,
        working_directory=working_directory,
    )

    theorem_string = 'by(metis fff order_le_less_trans)'

    env.post(f"<proceed after> {theorem_string}")
    env.post("<initialise>")
    print(env.post("<local facts and defs> default"))
    