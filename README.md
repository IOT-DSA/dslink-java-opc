# dslink-java-opc


## Usage

For the "add connection" action:

    name: any name
    host: the network name of the PC which is running the OPC server(s)
    domain: in most cases, this should be localhost
    user: name of the windows account (on the host PC) which is running the server(s)
    password: password of this windows account
Note that if you only need your connection for connecting to XML-DA servers, you only need the "name" parameter. XML-DA servers are not tied to a specific PC, so they may be accessed from any connection


For the "add server" action:

	name: any name
	server prog id: this should be something like "Kepware.KEPServerEX.V5" or "Matrikon.OPC.Simulation.1", however, the dslink will usually automatically discover the OPC servers running on the host PC and give you a drop-down list of server ids to choose from.
