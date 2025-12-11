package com.demo.springbootdemo.controller;

import com.demo.springbootdemo.configuration.JwtUtil;
import com.demo.springbootdemo.configuration.PasswordGenerator;
import com.demo.springbootdemo.entity.*;
import com.demo.springbootdemo.model.ApiResponse;
import com.demo.springbootdemo.model.ChangePasswordRequest;
import com.demo.springbootdemo.model.ResetPasswordResponse;
import com.demo.springbootdemo.model.SignUpRequest;
import com.demo.springbootdemo.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import jakarta.mail.MessagingException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.aspectj.weaver.ast.Not;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Service
public class UserController implements UserDetailsService {

    @Autowired private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TeamController teamController;
    @Autowired
    private PasswordGenerator passwordGenerator;

    @Autowired
    private EmailController emailController;

    @Autowired
    private CompanyController companyController;

    @Autowired
    private NotificationController notificationController;

    @Autowired
    private WebSocketService webSocketService;

    @Autowired
    private CompanySettingsController companySettingsController;

    @Value("${app.shared.verification-code-expire-in}")
    private long verificationExpireIn;

    @Value("${app.max.login.attempts}")
    private long maxLoginAttempts;

    private ScheduledFuture<?> futureTask;

    @Autowired
    @Qualifier("verificationCodeScheduler")
    private ThreadPoolTaskScheduler scheduler;

    @Value("${app.token.default.expiration}")
    private long EXPIRATION;

    private final JwtUtil jwtUtil;
    public UserController(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email);
        return new CustomUserDetails(user);
    }

    public void deleteUserEmailNotification(User user) throws MessagingException {
        emailController.sendDeleteUserEmail(
                user.getFirstname() + " " + user.getLastname(),
                user.getEmail(),
                user.getCompany().getName()
        );
    }

    public ApiResponse<User> addUser(SignUpRequest signUpRequest) throws MessagingException {
        ApiResponse<User> response = new ApiResponse<>();
        User existingUser = userRepository.findByEmail(signUpRequest.getUser().getEmail());
        if(existingUser != null){
            response.setStatus(HttpStatus.CONFLICT);
            response.setMessageLabel("auth_signup_used_email_error_message");
            response.setSuccess(false);
            response.setData(null);
            return response;
        }else {
            User user = signUpRequest.getUser();
            Company company = signUpRequest.getCompany();
            CompanySettings companySettings = new CompanySettings();
            companySettings = companySettingsController.saveSettings(companySettings);
            company.setSettings(companySettings);
            company.setAt(new Date().getTime());
            company = companyController.saveCompany(company);
            user.setCompany(company);
            user.setRole(Role.ADMIN);
            user.setFirstLogin(true);
            user.setLocked(false);
            user.setAttempts(0);
            user.setCreationDate(new Date().getTime());
            user.setSicknessLeaverSold(10F);
            user.setHolidaySold(28F);
            user.setProfilePictureUrl("assets/img/default_profile_picture.png");
            String generatedPassword = passwordGenerator.generateStrongPassword(companySettings);
            user.setPassword(passwordEncoder.encode(generatedPassword)); // hash the password
            user = userRepository.save(user);
            company.setMembers(user);
            company.setCompanyCreator(user);
            companyController.saveCompany(company);

            emailController.sendWelcomePasswordEmail(
                    user.getFirstname() + " " + user.getLastname(),
                    user.getEmail(),
                    generatedPassword,
                    company.getName()
            );
            response.setStatus(HttpStatus.OK);
            response.setMessageLabel("auth_signup_success_message");
            response.setData(user);
            response.setSuccess(true);

            return response;
        }
    }

    public String getNewPasswordAndSendMail(User user) throws MessagingException {
        String generatedPassword = passwordGenerator.generateStrongPassword(user.getCompany().getSettings());
        emailController.sendWelcomePasswordEmail(
                user.getFirstname() + " " + user.getLastname(),
                user.getEmail(),
                generatedPassword,
                user.getCompany().getName()
        );
        return passwordEncoder.encode(generatedPassword);
    }

    public User login(String email, String password) {
        User user = userRepository.findByEmail(email);
        if(user != null){
            boolean matches = passwordEncoder.matches(password, user.getPassword());
            if (matches) {
                return user;
            } else {
                user.setAttempts(user.getAttempts() + 1);
                if (user.getAttempts() > maxLoginAttempts) user.setLocked(true);
                userRepository.save(user);
            }
        }
        if(user != null && user.isLocked()) return user;
        return null;
    }

    public User getUserByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    public List<User> getUsersByRole(Role role) {
        return userRepository.findByRole(role);
    }

    public List<User> getUsersByCompany(User authUser) {
        List<User> users = userRepository.findByCompany(authUser.getCompany());
        users.remove(authUser);
        return users;
    }

    public User setUser(User user) {
        return userRepository.save(user);
    }

    public void scheduleAttributeChange(User user) {
        futureTask = scheduler.schedule(
                () -> {
                    user.setVerificationCode("");
                    userRepository.save(user);
                    System.out.println("Verification code expired");
                },
                new java.util.Date(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(verificationExpireIn))
        );
    }

    public ApiResponse<ResetPasswordResponse> resetPasswordMailConfirmation(String email) throws MessagingException {
        ApiResponse<ResetPasswordResponse> response = new ApiResponse<>();
        User user = getUserByEmail(email);
        if(user == null) {
            response.setData(null);
            response.setStatus(HttpStatus.UNAUTHORIZED);
            response.setMessageLabel("error_status_UNAUTHORIZED");
            response.setSuccess(false);
        }else if(user.isFirstLogin()){
            response.setData(null);
            response.setStatus(HttpStatus.UNAUTHORIZED);
            response.setMessageLabel("error_status_UNAUTHORIZED_set_password_first_login");
            response.setSuccess(false);
        }else if(user.isLocked()){
            response.setData(null);
            response.setStatus(HttpStatus.UNAUTHORIZED);
            response.setMessageLabel("error_status_UNAUTHORIZED_set_password_locked");
            response.setSuccess(false);
        }else{
            String code = passwordGenerator.generateCode(user.getCompany().getSettings());
            user.setVerificationCode(code);
            userRepository.save(user);
            if(user.getCompany().getSettings() != null) verificationExpireIn = user.getCompany().getSettings().getVerificationCodeExpireIn();
            scheduleAttributeChange(user);
            emailController.sendResetPasswordConfirmationEmail(email, code, user, verificationExpireIn);
            response.setStatus(HttpStatus.OK);
            ResetPasswordResponse resetPasswordResponse = new ResetPasswordResponse();
            resetPasswordResponse.setFullName(user.getFirstname() + " " + user.getLastname());
            resetPasswordResponse.setVerificationCodeLength(user.getCompany().getSettings().getVerificationCodeLength());
            resetPasswordResponse.setVerificationCodeExpireIn(verificationExpireIn);
            resetPasswordResponse.setPasswordMinLength(user.getCompany().getSettings().getPasswordMinLength());
            response.setData(resetPasswordResponse);
            response.setShowToast(false);
            response.setSuccess(true);
        }
        return response;
    }

    public ApiResponse<User> resetPasswordCodeCheck(String code, String email, HttpServletResponse httpResponse) {
        ApiResponse<User> response = new ApiResponse<>();
        User authUser = getUserByEmail(email);
        if(authUser == null){
            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR);
            response.setMessageLabel("error_status_INTERNAL_SERVER_ERROR");
            response.setSuccess(false);
            response.setDoLogout(true);
        }else{
            boolean status = authUser.getVerificationCode().equalsIgnoreCase(code);
            if(status){
                response.setData(authUser);
                response.setStatus(HttpStatus.OK);
                response.setShowToast(false);
                response.setSuccess(true);
            }else{
                response.setStatus(HttpStatus.UNAUTHORIZED);
                response.setShowToast(false);
                response.setSuccess(false);
            }

            response.setSuccess(status);
        }
        return response;
    }

    public void createNotification(User fromUser, User toUser, Long at, String titleLabel, String messageLabel){
        Notification notification = new Notification();
        notification.setFrom(fromUser);
        notification.setAt(at);
        notification.setTitleLabel(titleLabel);
        notification.setMessageLabel(messageLabel);
        notification.setTo(toUser);
        notification = notificationController.saveNotification(notification);
        webSocketService.sendNotification(notification);
    }

    public ApiResponse<Boolean> changePassword(ChangePasswordRequest changePasswordRequest) {
        ApiResponse<Boolean> response = new ApiResponse<>();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        User authUser = getUserByEmail(authentication.getPrincipal().toString());
        if(authUser == null){
            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR);
            response.setMessageLabel("error_status_INTERNAL_SERVER_ERROR");
            response.setSuccess(false);
            response.setDoLogout(true);
        }else{
            authUser.setPassword(changePasswordRequest.getPassword());
            authUser.setLocked(false);
            authUser.setAttempts(0);
            authUser.setLastPasswordResetDate(new Date().getTime());
            if(authUser.isFirstLogin()) {
                User toUser;

                Notification notification = new Notification();
                notification.setFrom(authUser);
                notification.setAt(new Date().getTime());
                notification.setTitleLabel("notification_new_joiner_title");
                notification.setMessageLabel("notification_new_joiner_message");
                if(authUser.getTeam() != null && authUser.getTeam().getManager() != null)
                    toUser = authUser.getTeam().getManager();
                else {
//                  send notification to company creator
                    Company authUserCompany = companyController.getMembersByUser(authUser);
                    toUser = authUserCompany.getCompanyCreator();
                }
                if(toUser != null && !toUser.getEmail().equals(authUser.getEmail()))
                    createNotification(
                            authUser,
                            toUser,
                            new Date().getTime(),
                            "notification_new_joiner_title",
                            "notification_new_joiner_message");
                authUser.setFirstLogin(false);
            }
            userRepository.save(authUser);
            response.setStatus(HttpStatus.OK);
            response.setMessageLabel("auth_forget_password_success_message");
            response.setSuccess(true);
        }
        return response;
    }

    @PostConstruct
    public void emptyAllVerificationCodes() {
        userRepository.findAll().forEach(user -> {
            user.setVerificationCode("");
            userRepository.save(user);
        });
    }

    public List<User> getAllEmployees(){
        return userRepository.findByRoleAndTeamIsNull(Role.EMPLOYEE);
    }

    public Team emptyUserTeam(List<String> remainingUsers, Team team) {
        if (team == null || team.getMembers() == null || remainingUsers == null) {
            return null;
        }

        // Clear the team reference inside each removed user
        for (String userEmail : remainingUsers) {
            User user = userRepository.findByEmail(userEmail);
            if (user.getTeam() != null && user.getTeam().equals(team)) {
                user.setTeam(null);
                user.getTeams().remove(team);
                if(!user.getRole().equals(Role.ADMIN)){
                    if(user.getTeams().isEmpty())
                        user.setRole(Role.EMPLOYEE);
                }
                userRepository.save(user);
            }
            // Also remove all users in remainingUsers from the team's user list
            team.getMembers().remove(user);
        }
        return teamController.saveTeam(team);
    }

    public void setUserTeam(List<String> teamMembers, Team team) {
        for (String userEmail : teamMembers) {
            User user = userRepository.findByEmail(userEmail);
            team.setMembers(user);
            if(user.getRole().equals(Role.EMPLOYEE))
                user.setTeam(team);
            if(user.equals(team.getManager()) &&
               !user.getTeams().contains(team)){
                user.setTeams(team);
            }else if(!user.equals(team.getManager())) {
                if(!user.getRole().equals(Role.ADMIN)) {
                    if(user.getTeams().isEmpty())
                        user.setRole(Role.EMPLOYEE);
                }
            }
            userRepository.save(user);
        }
        teamController.saveTeam(team);
    }

    public void addUsersToTeam(List<String> teamMembers, Team team) {
        for (String userEmail : teamMembers) {
            User user = userRepository.findByEmail(userEmail);
            team.setMembers(user);
            user.setTeam(team);
            if(user.equals(team.getManager())){
                user.setTeams(team);
            }else{
                if(!user.getRole().equals(Role.ADMIN)) {
                    if(user.getTeams().isEmpty())
                        user.setRole(Role.EMPLOYEE);
                }
            }
            userRepository.save(user);
        }
        teamController.saveTeam(team);
    }

    public User save(User user) {
        return userRepository.save(user);
    }

    public void deleteUser(User user) {
        userRepository.delete(user);
    }

    // Runs at 23:59 on the last day of every month
    @Scheduled(cron = "59 23 L * *")
    public void setUsersHolidaySold() {
        List<User> users = userRepository.findAll();
        users.forEach(user -> {
            Company userCompany = user.getCompany();
            Float currentHolidaySold = user.getHolidaySold();
            user.setHolidaySold(currentHolidaySold + userCompany.getSettings().getHolidayDaysPerMonth());
            userRepository.save(user);
        });
    }
    // Runs at 23:59 each 31 dec at 23:59
    @Scheduled(cron = "59 23 31 12 *")
    public void setUsersAnnualSicknessLeaverSold() {
        List<User> users = userRepository.findAll();
        users.forEach(user -> {
            Company userCompany = user.getCompany();
            user.setSicknessLeaverSold((float) userCompany.getSettings().getSicknessLeaverDaysPerYear());
            userRepository.save(user);
        });
    }
}

