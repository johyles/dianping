package com.hmdp.utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class test1 {
    public static void main(String[] args) {
        List<Node> list = new ArrayList<>();
        list.add(new Node("s1","s2"));
        list.add(new Node("s2","s3"));
        list.add(new Node("s4","s3"));
        list.add(new Node("s5","s6"));
        list.add(new Node("s3","null"));
        list.add(new Node("s6","s7"));
        List<TreeNode> treeNodes = convertListToTree(list);

        // 遍历每个根节点
        printTree(treeNodes);
    }
    //实现下方函数
    public static List<TreeNode> convertListToTree(List<Node> list) {
        HashMap<String, TreeNode> map = new HashMap<>();

        // 构造一个 map
        for (Node node : list) {
            String id = node.id;
            String parentId = node.parentId;
            TreeNode treeNode = new TreeNode();
            treeNode.id = id;
            treeNode.children = new ArrayList<>();
            map.put(id, treeNode);
        }
        List<TreeNode> resultList = new ArrayList<>();

        // 遍历，构造森林
        for (Node node : list) {
            TreeNode curTreeNode = map.get(node.id);
            TreeNode parentTreeNode = map.get(node.parentId);

            if (parentTreeNode == null) {
                // 如果取不到父节点，说明该节点是树的根节点
                resultList.add(curTreeNode);
            } else {
                // 取到父节点，将当前节点加入父节点的 children 集合中
                parentTreeNode.children.add(curTreeNode);
            }
        }

        return resultList;
    }
    public static void printTree(List<TreeNode> roots) {
        if (roots == null) {
            return;
        }
        for (TreeNode node : roots) {
            System.out.println("{ id:\"" + node.id + "\", children:[");
            printTree(node.children);
            System.out.println("]},");
        }
    }

}


class Node {
    String id;
    String parentId;
    public Node(){}
    public Node(String id, String parentId){
        this.id = id;
        this.parentId  = parentId;
    }
}

class TreeNode {
    String id;
    List<TreeNode> children;
}
