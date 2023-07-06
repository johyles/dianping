public class  singletonDCL{
    private static volatile singletonDCL instance;
    private singletonDCL(){

    }
    public static singletonDCL getInstance(){
        if(instance == null){
            synchronized (singletonDCL.class){
                if(instance == null){
                    instance = new singletonDCL();
                }
            }
        }
        return instance;
    }
}
