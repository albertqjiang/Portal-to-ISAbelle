FROM eclipse-temurin:11

RUN apt-get update && \
    apt-get install -y python3-pip unzip zip vim tmux

RUN python3 -m pip install grpcio func_timeout protobuf==3.20.1

RUN curl -s "https://get.sdkman.io" | bash
SHELL ["/bin/bash", "-c"]

# Setting a work directory for PISA
WORKDIR /pisa

RUN apt-get install -y git && \
    git clone https://github.com/albertqjiang/Portal-to-ISAbelle.git . && \
    source "$HOME/.sdkman/bin/sdkman-init.sh" && \
    sdk install java 11.0.11-open && \
    sdk install sbt && \
    sbt compile && \
    sbt assembly

RUN wget https://isabelle.in.tum.de/dist/Isabelle2022_linux.tar.gz && \
    tar -xzf Isabelle2022_linux.tar.gz

RUN echo 'alias isabelle=/pisa/Isabelle2022/bin/isabelle' >> ~/.bashrc

RUN /pisa/Isabelle2022/bin/isabelle build -b -D /pisa/Isabelle2022/src/HOL/ -j 20

# Comment out the next two lines if you want to build PISA without AFP
RUN wget https://archive.org/download/isabelle2022_afp20221206_heaps/isabelle2022heaps.tar.gz

RUN tar -xzf isabelle2022heaps.tar.gz -C /root/.isabelle

EXPOSE 8000-8200