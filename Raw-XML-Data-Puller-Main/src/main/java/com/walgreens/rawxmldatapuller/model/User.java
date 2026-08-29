package com.walgreens.rawxmldatapuller.model;

public final class User {

    private final String  id;
    private final String  username;
    private final String  fullName;
    private final String  email;
    private final String  role;       // "ADMIN", "USER", or "BUSINESS"
    private final boolean active;
    private final boolean mustChangePassword;
    private final String  lastLogin;  // pre-formatted string for display

    /** Used at login — active/lastLogin not needed at runtime. */
    public User(String id, String username, String fullName, String email,
                String role, boolean mustChangePassword) {
        this(id, username, fullName, email, role, true, mustChangePassword, null);
    }

    /** Full constructor used when listing all users in the admin panel. */
    public User(String id, String username, String fullName, String email,
                String role, boolean active, boolean mustChangePassword, String lastLogin) {
        this.id                 = id;
        this.username           = username;
        this.fullName           = fullName;
        this.email              = email;
        this.role               = role;
        this.active             = active;
        this.mustChangePassword = mustChangePassword;
        this.lastLogin          = lastLogin;
    }

    public String  getId()                { return id; }
    public String  getUsername()          { return username; }
    public String  getFullName()          { return fullName != null && !fullName.isBlank() ? fullName : username; }
    public String  getEmail()             { return email != null ? email : ""; }
    public String  getRole()              { return role != null ? role.toUpperCase() : "USER"; }
    public boolean isActive()             { return active; }
    public boolean isMustChangePassword() { return mustChangePassword; }
    public String  getLastLogin()         { return lastLogin != null ? lastLogin : "—"; }
    public boolean isAdmin()              { return "ADMIN".equalsIgnoreCase(role); }
    public boolean isBusiness()           { return "BUSINESS".equalsIgnoreCase(role); }

    @Override
    public String toString() { return getFullName() + " (" + username + ")"; }
}
