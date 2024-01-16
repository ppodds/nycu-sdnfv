FROM ubuntu:22.04

RUN apt update && \
    apt install -y net-tools iproute2 iputils-ping && \
    apt clean

CMD ["/bin/bash"]