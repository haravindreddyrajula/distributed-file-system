import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.UnknownHostException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class Master extends UnicastRemoteObject implements Storage {

    private Map<String, List<String>> fileLocation;
    private Map<Storage, List<String>> replicaDetails;
    private Set<Storage> replicaInstances;
    private ConcurrentHashMap<String, byte[]> fileContentStorage;

    private static ServerSocket ssock;

    // private String[] files;
    // String environment;

    // Constructor
    public Master() throws RemoteException {
        super();
        fileLocation = new HashMap<String, List<String>>();
        replicaDetails = new HashMap<Storage, List<String>>();
        replicaInstances = new HashSet<Storage>();
        fileContentStorage = new ConcurrentHashMap<String, byte[]>();

        try {
            getFiles();
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Error in getting file logs in master constructor");
        }
    }

    // relicas use this method to get registered of its instance
    // Master method only -> 1
    public String[] register(String IP_STORAGE_SERVER, int PORT_STORAGE_SERVER, String[] files, Storage command_stub)
            throws RemoteException, NotBoundException {

        replicaInstances.add(command_stub); // check if server is active
        System.out.println("Replica: " + IP_STORAGE_SERVER + " got connected to Master");

        if (replicaDetails.get(command_stub) == null) {
            List<String> temp = new ArrayList<String>();
            temp.add(new String(IP_STORAGE_SERVER));
            temp.add(new String(PORT_STORAGE_SERVER + ""));
            replicaDetails.put(command_stub, temp);
        }
        return new String[2];
    }

    // get all files/folders names
    private void getFiles() throws Exception {
        // String path = Paths.get("").toAbsolutePath().toString();
        File curDir = new File("."); // returns all files from the cur dir
        File[] filesList = curDir.listFiles(); // lists all files and sub dir
        for (File f : filesList)
            fileLocation.put(f.getName(), new ArrayList<String>()); // [key, []]
    }

    // binding remote object with given name in registry
    public synchronized void start(String port, String tcp) throws NumberFormatException, IOException {
        // Creates and exports a Registry instance on the local host that accepts
        // requests on the specified port.
        Registry registry = LocateRegistry.createRegistry(Integer.parseInt(port));

        /**
         * Replaces the binding for the specified <code>name</code> in this registry
         * with the supplied remote reference. If there is an existing binding for the
         * specified <code>name</code>, it is discarded.
         **/
        registry.rebind("Master", new Master());

        ssock = new ServerSocket(Integer.parseInt(tcp));
    }

    // writing into master server and replicas
    public boolean write(String clientIP, String PORT, String path, String fileDetail)
            throws UnknownHostException, IOException {

        new Thread(new Runnable() {
            public void run() {
                System.out.println("Given File: " + path + " from client");

                try {

                    Socket socket = ssock.accept();
                    FileOutputStream fos = new FileOutputStream(path);
                    BufferedOutputStream bos = new BufferedOutputStream(fos);
                    InputStream is = socket.getInputStream();

                    // TODO : Implement writing on server after replica writing is successful
                    int bytesRead = 0;
                    byte[] contents = new byte[10000];
                    while ((bytesRead = is.read(contents)) != -1) {
                        // bos.write(contents); //file size: contents size 10000
                        bos.write(contents, 0, bytesRead); // size: actual size of file
                    }

                    // Writing into the replicas
                    System.out.println("Writing into the replicas has started.. ");
                    List<Boolean> concurrencylist = new ArrayList<>();
                    for (Storage stub : replicaInstances) {
                        List<String> s = replicaDetails.get(stub);

                        Socket socketre = new Socket(InetAddress.getByName(s.get(0)), Integer.parseInt(s.get(1)));
                        OutputStream os = socketre.getOutputStream();
                        os.write(contents);

                        // replicaip, replica tcp port, file path and clientip
                        boolean flag = stub.writePhaseone(s.get(0), s.get(1), path, clientIP);
                        concurrencylist.add(flag);

                        os.flush();
                        os.close();
                        socketre.close();
                    }

                    if (!concurrencylist.contains(false)) {
                        for (Storage stub : replicaInstances)
                            stub.writePhaseTwo(clientIP, path); // clientip and file path

                        // giving authorization block
                        if (fileDetail.equalsIgnoreCase("new")) {
                            fileLocation.get(path).add(clientIP);
                            System.out.println("Client: " + clientIP + " has the access to File: " + path);
                        }

                        // if (fileLocation.get(path).size() == 0){
                        // fileLocation.get(path)
                        // }

                    }

                    bos.flush();
                    bos.close();
                    fos.flush();
                    fos.close();
                    socket.close();
                    System.out.println("File saved successfully! at Primary server");

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

        }).start();
        return true;

    }

    // Reading from file
    public void read(String path) throws IOException, RemoteException {
        new Thread(new Runnable() {
            public void run() {
                try {
                    File file = new File(path);
                    FileInputStream fis = new FileInputStream(file);
                    BufferedInputStream bis = new BufferedInputStream(fis);
                    byte[] contents;
                    long fileLength = file.length();
                    long current = 0;
                    while (current != fileLength) {
                        int size = 10000;
                        if (fileLength - current >= size)
                            current += size;
                        else {
                            size = (int) (fileLength - current);
                            current = fileLength;
                        }
                        contents = new byte[size];
                        bis.read(contents, 0, size);
                        fileContentStorage.put(path, contents); // Assuming max file size is 10kb
                    }
                    bis.close();
                } catch (Exception e) {
                    e.printStackTrace();
                    // return false;
                }

            }
        }).start();
    }

    // Deleting file/folder with 2pc
    public boolean remove(String path) throws RemoteException, IOException {
        try {
            List<Storage> failedList = new ArrayList<>(); // to store failed replicas

            // PHASE 1
            for (Storage stub : replicaInstances) {
                if (!stub.remove(path)) {
                    failedList.add(stub);
                }
            }

            // PHASE 2
            if (failedList.size() != 0) {
                for (Storage stub : replicaInstances) {
                    if (!failedList.contains(stub)) {
                        // TODO: Impl in replica block and master block
                        // before del read the contents of the file
                        // if its folder.. ignore

                    }
                }

                System.err.println("Folder/File: " + path + " is failed..");
                return false;
            } else {
                System.out.println("Folder/File: " + path + "created successfully");
                return true;
            }

        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }

    }

    // create file/folder with 2pc
    public boolean create(String path, String type) throws RemoteException, IOException {
        try {

            List<Storage> failedList = new ArrayList<>(); // to store failed replicas

            // PHASE 1
            for (Storage stub : replicaInstances) {
                // file path, mkdir, new/existing
                if (!stub.create(path, type)) {
                    failedList.add(stub);
                }
            }

            // phase 2
            if (failedList.size() != 0) {
                for (Storage stub : replicaInstances) {
                    if (!failedList.contains(stub)) {
                        stub.remove(path);
                    }
                }
                System.err.println("Folder/File: " + path + " is failed..");
                return false;
            } else {
                System.out.println("Folder/File: " + path + "created successfully");
                return true;
            }

            //

            // for directory
            // if (type.equals("mkdir")) {
            // if (!serverpathdir.isDirectory()) { // not a directory or doesnt exist
            // // mkdir = true: if new, false: if already exists
            // if (serverpathdir.mkdir()) { // if folder got created at server

            // List<Storage> failedList = new ArrayList<>(); // to store failed replicas

            // // Creating and getting replies (phase one)
            // for (Storage stub : replicaInstances) {
            // // file path, mkdir, new/existing
            // if (!stub.create(path, type)) {
            // failedList.add(stub);
            // }
            // }

            // // phase 2
            // if (failedList.size() != 0) {
            // for (Storage stub : replicaInstances) {
            // if (!failedList.contains(stub)) {
            // stub.remove(path);
            // }
            // }
            // System.err.println("Folder: " + path + " is failed..");
            // } else {
            // System.out.println("Folder: " + path + "created successfully");
            // }

            // } else {
            // System.err.println("Failed to create folder " + path + " in master server");
            // }

            // } else {
            // System.err.println("directory given by already exists in master server");
            // }

            // } // for file
            // else {
            // if (!serverpathdir.isFile()) {

            // if (serverpathdir.createNewFile()) {

            // // Same impl as the mkdir folder above
            // List<Storage> failedList = new ArrayList<>(); // to store failed replicas

            // // Creating and getting replies (phase one)
            // for (Storage stub : replicaInstances) {
            // // file path, mkdir, new/existing
            // if (!stub.create(path, type)) {
            // failedList.add(stub);
            // }
            // }

            // // phase 2
            // if (failedList.size() != 0) {
            // for (Storage stub : replicaInstances) {
            // if (!failedList.contains(stub)) {
            // stub.remove(path);
            // }
            // }
            // System.err.println("file: " + path + " is failed..");
            // } else {
            // System.out.println("file: " + path + "created successfully");
            // }
            // }

            // } else {
            // System.err.println("file given already exists in master server");
            // }
            // }

        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    // Renaming File/dir
    public boolean rename(String newFile, String oldFile) throws RemoteException, IOException {

        try {

            List<Storage> failedList = new ArrayList<>(); // to store failed replicas

            // PHASE 1
            for (Storage stub : replicaInstances)
                if (!stub.rename(newFile, oldFile))
                    failedList.add(stub);

            // phase 2
            if (failedList.size() != 0) {
                for (Storage stub : replicaInstances) {
                    if (!failedList.contains(stub)) {
                        stub.rename(oldFile, newFile);
                    }
                }
                System.err.println("Folder/File: " + oldFile + " renaming failed... ");
                return false;
            } else {
                System.out.println("Folder/File: " + oldFile + " renamed to " + newFile + " successfully");
                return true;
            }

        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    // TODO
    public boolean directoryimpl(String clientIP, String PORT, String path, String type, String fileDetail)
            throws UnknownHostException, IOException, SecurityException {

        new Thread(new Runnable() {
            public void run() {
                System.out.println("Folder: " + path);
                try {
                    File serverpathdir = new File(path);
                    // Creating dir
                    if (type.equals("mkdir")) {
                        if (!serverpathdir.isDirectory()) { // not a directory or doesnt exist
                            // mkdir = true: if new, false: if already exists
                            if (serverpathdir.mkdir()) { // if folder got created at server
                                if (create(path, type))
                                    serverpathdir.delete();
                            } else {
                                System.err.println("Failed to create folder " + path + " in master server");
                            }
                        } else {
                            System.err.println("directory given by " + clientIP + " already exists in master server");
                        }
                    }

                    // Creating File
                    else if (type.equals("create")) {
                        if (!serverpathdir.isFile()) {
                            if (serverpathdir.createNewFile()) {
                                if (create(path, type))
                                    serverpathdir.delete();
                            } else {
                                System.err.println("Failed to create file " + path + " in master server");
                            }
                        } else {
                            System.err.println("file given already exists in master server");
                        }

                    }

                    // deleting directory
                    else if (type.equals("rmdir")) {
                        if (serverpathdir.isDirectory()) {
                            if (serverpathdir.list().length != 0) {
                                System.err.println(
                                        "This folder in master server cant be deleted because it has contents in it");
                            } else {
                                if (remove(path)) {
                                    serverpathdir.delete();
                                    System.out.println("Folder: " + path + " successfully deleted.. ");
                                } else {
                                    System.err.println("Failed to delete the folder: " + path);
                                }
                            }
                        } else {
                            System.err.println("No such directory exists at master server");
                        }
                    }

                    // deleting file
                    else if (type.equals("remove")) {
                        if (serverpathdir.isFile()) {
                            if (remove(path)) {
                                serverpathdir.delete();
                                System.out.println("file: " + path + " successfully deleted.. ");
                            } else {
                                System.err.println("Failed to delete the file: " + path);
                            }
                        } else {
                            System.err.println("No such file exists at master server");
                        }
                    }

                    // Renaming file/dir
                    else if (type.equals("rename")) {
                        if (path.contains(",")) {
                            String newFile = path.split(",")[1];
                            String oldFile = path.split(",")[0];
                            File n = new File(newFile);
                            File o = new File(oldFile);
                            if (n.isDirectory() || n.isFile()) {
                                System.err.println("the path: " + newFile + " already exists");
                            } else {
                                if (o.isDirectory() || o.isFile()) {
                                    if (rename(newFile, oldFile)) {
                                        o.renameTo(n);
                                    } else {
                                        System.err.println("Failed to rename the file/dir");
                                    }
                                } else {
                                    System.err.println("No such file/folder: " + oldFile + " exists");
                                }
                            }
                        } else {
                            System.err.println("Plz provide the new file,old file");
                        }
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

        }).start();
        return true;

    }

    public List<String> list() throws Exception {
        return new ArrayList<>(fileLocation.keySet());
    }

    public Map<String, List<String>> getFileMap() throws Exception {
        return fileLocation;
    }

    // main function
    public static void main(String args[]) throws NumberFormatException, IOException {

        // Checking the INPUTS...
        /**
         * java Master
         * args[0] -> master ip
         * args[1] -> master port
         * args[2] -> tcp
         */
        if (args.length < 3) {
            System.err.println("Incorrect arguments length.. Please give IP address, port number and tcp");
            System.exit(1);
        }

        System.setProperty("java.rmi.server.hostname", args[0]);
        String value = System.getProperty("java.rmi.server.hostname");
        if (value == null) {
            System.err.println("error in configuring RMI");
            System.exit(1);
        }
        if (System.getSecurityManager() != null) {
            System.out.println("Security manager error");
        }

        new Master().start(args[1], args[2]);
        System.out.println("\n Master/Primary Server is listening on port = " + args[1]); // args[1] is port number
        System.out.println("\n Socket listening on port : " + args[2]);

    }

    @Override
    public byte[] read() throws RemoteException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public List<String> getStorage(String file) throws RemoteException, FileNotFoundException, IOException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public boolean put(String IP, String PORT, String path) throws Exception {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean writePhaseTwo(String IP, String path) throws UnknownHostException, IOException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean writePhaseone(String IP, String PORT, String path, String client)
            throws UnknownHostException, IOException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean directoryimplReplica(String path, String type, String fileDetail)
            throws UnknownHostException, IOException {
        // TODO Auto-generated method stub
        return false;
    }

}