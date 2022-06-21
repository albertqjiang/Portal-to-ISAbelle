## Installation
1. **Scala configuration**

   Install SDKMAN
    ```shell
    curl -s "https://get.sdkman.io" | bash
    source .bashrc
    ```
   Try
    ```shell
    sdk help
    ```
   to makes ure sdk is properly installed.

   Install JAVA 11 and sbt
    ```shell
    sdk install java 11.0.11-open
    sdk install sbt
    ```
2. **Clone project and make sure it compiles**

    ```shell
    git clone https://github.com/albertqjiang/Portal-to-ISAbelle.git
    cd Portal-to-ISAbelle
    sbt compile
    ```

3. **Configure Isabelle**

   Go back to home directory first and download isabelle2021
    ```shell
    cd ~
    wget https://isabelle.in.tum.de/website-Isabelle2021/dist/Isabelle2021_linux.tar.gz
    tar -xzf Isabelle2021_linux.tar.gz
    alias isabelle=~/Isabelle2021/bin/isabelle
    ``` 

4. **Build Isabelle HOL**
   ```shell
   isabelle build -b -D Isabelle2021/src/HOL
   ```
   This takes ~6 hours of CPU time. The actual time depends on the number of CPUs you have.

5. **Download and build afp**
   ```shell
   wget https://www.isa-afp.org/release/afp-2021-10-22.tar.gz
   tar -xzf afp-2021-10-22.tar.gz
   export AFP=afp-2021-10-22/thys
   isabelle build -b -D $AFP
   ```
   This takes ~24 hours on 8 CPUs. We can extract ~93% of all afp theory files.

   We built the heap images of Isabelle2021 with afp-2021-10-22 for linux machines (ubuntu). You can download it at:
   https://storage.googleapis.com/n2formal-public-data/isabelle_heaps.tar.gz
   and decompress it as ~/.isabelle.

   Note: this does not always work on different operating systems.

6. **Extract the test theorems**
   The universal test theorems contains 3000 theorems with their file paths and names. The first 600 of them are packaged as "quick" theorems if you have no patience testing all 3000 out.
   ```shell
   tar -xzf universal_test_theorems.tar.gz
   ```