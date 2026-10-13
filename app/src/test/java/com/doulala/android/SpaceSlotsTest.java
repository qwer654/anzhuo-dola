package com.doulala.android;
import org.junit.Test;
import static org.junit.Assert.*;
public class SpaceSlotsTest {
    @Test public void all64StableIdentitiesAreValidAndUnique(){
        java.util.Set<String> names=new java.util.HashSet<>();
        for(int i=0;i<64;i++){assertTrue(SpaceSlots.isSuffix(SpaceSlots.suffix(i)));assertTrue(names.add(SpaceSlots.className(i)));}
        assertEquals(64,names.size());assertEquals("space7",SpaceSlots.suffix(7));assertEquals("space63",SpaceSlots.suffix(63));
    }
    @Test public void invalidSuffixesCannotSelectAnotherProfile(){for(String s:new String[]{"space64","space-1","space01","space99","space1/../space0","space","main",""})assertFalse(s,SpaceSlots.isSuffix(s));}
    @Test(expected=IllegalArgumentException.class) public void slotsStopAt64(){SpaceSlots.suffix(64);}
    @Test public void exitIpResponseAcceptsOnlyLiterals() throws Exception {
        assertEquals("203.0.113.1",ExitIpClient.validate(" 203.0.113.1\n"));
        assertTrue(ExitIpClient.validate("2001:db8::1").contains(":"));
        for(String s:new String[]{"example.com","127.1","<html>failed</html>","", "300.1.1.1"}){
            try{ExitIpClient.validate(s);fail("Accepted "+s);}catch(java.io.IOException expected){}
        }
    }
}
