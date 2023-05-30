from pisa_client import initialise_env


if __name__ == '__main__':
    env = initialise_env(
        8000, 
        "/pisa/Isabelle2022", 
        "/pisa/Isabelle2022/src/HOL/Computational_Algebra/Primes.thy",
        "/pisa/Isabelle2022/src/HOL/Computational_Algebra"
    )
    env.proceed_to_line('end', 'before')
    env.initialise()
    
    for premise, premise_defn in env.get_premises_and_their_definitions("default", "prime_int_naive", "by (auto simp add: prime_int_iff')"):
        print("~"*80)
        print(f"Premise name: {premise}")
        print(f"Premise defn: {premise_defn}")
