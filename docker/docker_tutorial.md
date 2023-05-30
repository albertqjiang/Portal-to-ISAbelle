# How to install PISA with Docker and start using it

This guide is designed to help you easily install and start using PISA via Docker.
Docker simplifies the deployment process by encapsulating the software and its dependencies into a single object called a Docker image. With Docker, you don't have to worry about installing all the necessary software and libraries manually.

## 1. Prerequisites
Before getting started, please ensure that you have Docker installed on your machine.

You should also make sure that you have sufficient disk space. The PISA image is available in two versions:
- the first one is PISA with Isabelle standard library (you will need at least 10GB of disk space).
- the second one is PISA with Isabelle standard libary and the Archive of Formal Proofs (you will need at least 66GB of disk space).

## 2. Pulling the PISA image from Docker and running the container
Open a terminal and pull the Docker image you want to use. If you want to use PISA with the standard library, type:
```
$ docker pull dsantosmarco/pisa:pisa-sl
```
If you prefer to use PISA with the standard library and the Archive of Formal Proofs, you should type:
```
$ docker pull dsantosmarco/pisa:pisa-sl-afp
```
Once the image is pulled, you can run a Docker container.
Depending on what PISA image you chose, type either:
```
$ docker run -it --entrypoint=/bin/sh dsantosmarco/pisa:pisa-sl
```
or:
```
$ docker run -it --entrypoint=/bin/sh dsantosmarco/pisa:pisa-sl-afp
```
Note that `--entrypoint=/bin/sh` is used to run the container in bash environment instead of jshell.

## 3. Launching a PISA server and running test_client.py
Once the container is running, you can go to `/pisa/target/scala-2.13` and use `tmux` to open a new terminal window:
```
$ cd /pisa/target/scala-2.13
$ tmux
```
You can then run the following command to launch a PISA server in this new window:
```
$ java -cp PISA-assembly-0.1.jar pisa.server.PisaOneStageServer8000
```
You can then create an other window with `tmux` (using `Ctrl+B C`).
In this new window, you can go to `/pisa/src/main/python` and run the `test_client.py` file:
```
$ cd /pisa/src/main/python
$ python3 test_client.py
```
If everything worked, you should see the following printed in your terminal:
```
----------Path to Isabelle source----------
/pisa/Isabelle2022
----------Path to Isabelle working directory----------
/pisa/Isabelle2022/src/HOL/Computational_Algebra
----------Path to Isabelle theory file----------
/pisa/Isabelle2022/src/HOL/Computational_Algebra/Primes.thy
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
Premise name: Primes.prime_int_iff'
Premise defn:  prime_int_iff': fixes p :: "int" shows "prime p = (1 < p \<and> (\<forall>n\<in>{2..<p}. \<not> n dvd p))"
```

## Additional info: Build the PISA images yourself
If you prefer to build the PISA images yourself, you can use Docker to build them from the Dockerfile we added to the current folder "docker".
We used the same Dockerfile for both pisa-sl and pisa-sl-afp. If you want to build pisa-sl (without afp) you should comment out two lines of the Dockerfile (these are indicated in the Dockerfile).