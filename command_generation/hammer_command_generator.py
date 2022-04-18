number_of_processes = input("Enter the number of processes you want to run at the same time:\n").strip()
number_of_processes = int(number_of_processes)
import glob
import os
script = 'echo "y" | sbt "runMain pisa.agent.PisaHammerTest {}"'

total_cmds = list()
total_files = 0
for file_name in glob.glob("/home/qj213/Portal-to-ISAbelle/universal_test_theorems/test_name_*.json", recursive=True):
    number = int(file_name.split("/")[-1].rstrip(".json").split("_")[-1])
    if number <= 1000:
        total_cmds.append(script.format(file_name))
        total_files += 1

process_number_to_cmds = {i: [] for i in range(number_of_processes)}
print("A total of {} files are due to be generated".format(total_files))
for i, cmd in enumerate(total_cmds):
    process_number_to_cmds[i%number_of_processes].append(cmd)

for process_number, process_cmds in process_number_to_cmds.items():
    with open("scripts/eval_hammer_{}.sh".format(process_number), "w") as f:
        for process_cmd in process_cmds:
            f.write(process_cmd+"\n")
            f.write("PIDmain=$!\n")
            f.write("wait $PIDmain\n")
            f.write("ps -ef | grep z3 | awk '{print $2}' | xargs kill -9\n")
            f.write("ps -ef | grep veriT | awk '{print $2}' | xargs kill -9\n")
            f.write("ps -ef | grep cvc4 | awk '{print $2}' | xargs kill -9\n")
            f.write("ps -ef | grep eprover | awk '{print $2}' | xargs kill -9\n")
            f.write("ps -ef | grep SPASS | awk '{print $2}' | xargs kill -9\n")
