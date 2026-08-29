package com.walgreens.rawxmldatapuller.util;

import com.walgreens.rawxmldatapuller.model.User;

public final class SessionContext {

    private static volatile User currentUser;

    private SessionContext() {}

    public static void  setCurrentUser(User user) { currentUser = user; }
    public static User  getCurrentUser()           { return currentUser; }
    public static boolean isLoggedIn()             { return currentUser != null; }
    public static void  clear()                    { currentUser = null; }
}

