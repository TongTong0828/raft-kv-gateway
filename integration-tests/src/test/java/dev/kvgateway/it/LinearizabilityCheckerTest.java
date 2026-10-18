package dev.kvgateway.it;
import org.junit.jupiter.api.Test;
import java.util.*;
import static dev.kvgateway.it.LinearizabilityChecker.*;
import static org.junit.jupiter.api.Assertions.*;
class LinearizabilityCheckerTest {
  @Test void permitsOverlappingWinnerAndCommittedUnknown(){assertTrue(check(List.of(new Op(0,4,"put","a",0,"OK",7),new Op(1,5,"put","b",0,"CONFLICT",0),new Op(6,8,"read","a",0,"OK",7),new Op(9,10,"put","c",7,"UNKNOWN",0),new Op(11,12,"read","c",0,"OK",9))));}
  @Test void rejectsStaleReadAndTwoCasWinners(){assertFalse(check(List.of(new Op(0,1,"put","a",0,"OK",2),new Op(2,3,"read",null,0,"NOT_FOUND",0))));assertFalse(check(List.of(new Op(0,4,"put","a",0,"OK",2),new Op(1,5,"put","b",0,"OK",3))));}
  @Test void permitsUnknownOmissionButNeverFabricatesObservedValue(){assertTrue(check(List.of(new Op(0,1,"put","a",0,"UNKNOWN",0),new Op(2,3,"read",null,0,"NOT_FOUND",0))));assertFalse(check(List.of(new Op(0,1,"put","a",0,"UNKNOWN",0),new Op(2,3,"read","b",0,"OK",5))));}
}
