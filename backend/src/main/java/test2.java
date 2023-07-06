import java.util.List;

class ListNode{
    int val;
    ListNode next;
    ListNode(){

    }
    ListNode(int val){
        this.val = val;
    }
    ListNode(int val,ListNode next){
        this.val = val;
        this.next = next;
    }
}
public class test2 {

    public static void  main(String[] args){
        int[] num={2,3,4,5,6,7};
        ListNode ret = new ListNode();
        ListNode q = ret;
        for(int i=0;i<num.length;i++){
            ListNode p = new ListNode(num[i]);
            q.next = p;
            q = p;
        }
        q.next = null;
        ListNode head = ret.next;
        ret.next = null;
        head = reverse(head);
        while(head!=null) {
            System.out.print(head.val + " ");
            head = head.next;
        }
    }
    public static ListNode reverse(ListNode head){
        if(head == null)
            return head;
        ListNode ret = new ListNode();
        ret.next = null;
        ListNode p =null;
        while(head!=null){
            p = head.next;
            head.next = ret.next;
            ret.next = head;
            head = p;
        }
        return ret.next;
    }
}
