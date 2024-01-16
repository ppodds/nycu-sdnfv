all: up

up:
	docker compose up -d
	sudo ovs-vsctl add-br ovs-br
	sudo ovs-vsctl set bridge ovs-br protocols=OpenFlow14
	sudo ovs-vsctl set-controller ovs-br tcp:127.0.0.1:6653
	sudo ovs-docker add-port ovs-br ovs R1 --ipaddress="172.20.0.2/16"
	sudo ovs-docker add-port ovs-br ovs R2 --ipaddress="172.20.0.3/16"
	sudo ovs-docker add-port ovs-br ovs R3 --ipaddress="172.20.0.4/16"
	
clean:
	sudo ovs-docker del-port ovs-br ovs R1
	sudo ovs-docker del-port ovs-br ovs R2
	sudo ovs-docker del-port ovs-br ovs R3
	sudo ovs-vsctl del-br ovs-br
	docker compose down