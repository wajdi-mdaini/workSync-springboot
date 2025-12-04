package com.demo.springbootdemo.services;

import com.demo.springbootdemo.configuration.JwtUtil;
import com.demo.springbootdemo.controller.CompanyController;
import com.demo.springbootdemo.controller.UserController;
import com.demo.springbootdemo.entity.Company;
import com.demo.springbootdemo.entity.Role;
import com.demo.springbootdemo.entity.Team;
import com.demo.springbootdemo.entity.User;
import com.demo.springbootdemo.model.*;
import jakarta.mail.MessagingException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(path = "/auth")
public class LoginService {

    private final JwtUtil jwtUtil;
    public LoginService(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Autowired
    private UserController userController;

    @Autowired
    private CompanyController companyController;

    @Autowired
    private SharedSettings sharedSettings;

    @Value("${app.token.default.expiration}")
    private int EXPIRATION;

    @Value("${app.front.base.path}")
    private String frontBasePath;

    @RequestMapping(path = "/login", method = RequestMethod.POST)
    public ResponseEntity<ApiResponse<LoginResponse>> login(@RequestBody LoginRequest loginRequest,
                                                            HttpServletResponse httpResponse) {
        ApiResponse<LoginResponse> response = new ApiResponse<>();
        User loggedInUser = userController.login(loginRequest.getEmail(),loginRequest.getPassword());
        Company company = companyController.getMembersByUser(loggedInUser);
        if( loggedInUser != null && company != null){
            if(loggedInUser.isLocked()) {
                response.setData(null);
                response.setStatus(HttpStatus.LOCKED);
                response.setSuccess(false);
                response.setMessageLabel("auth_signin_blocked_user_error_message");
            }else{
                loggedInUser.setAttempts(0);
                userController.save(loggedInUser);
                LoginResponse loginResponse = new LoginResponse();
                loginResponse.setUser(loggedInUser);
                loginResponse.setCompany(company);
                String jwtToken = jwtUtil.generateToken(loggedInUser.getEmail(),company);
                response.setData(loginResponse);
                response.setStatus(HttpStatus.OK);
                response.setSuccess(true);
                response.setShowToast(false);
                Cookie cookie = new Cookie("jwt", jwtToken);
                cookie.setHttpOnly(true); // protect from JavaScript
                cookie.setSecure(frontBasePath.startsWith("https://"));   // only HTTPS
                cookie.setPath("/");      // available for the whole domain
                if(company.getSettings() != null){
                    cookie.setMaxAge(company.getSettings().getJwtTokenExpireIn() * 60);
                }else{
                    cookie.setMaxAge(EXPIRATION * 60);
                }
                httpResponse.addCookie(cookie);

            }

        }else {
            response.setData(null);
            response.setStatus(HttpStatus.UNAUTHORIZED);
            response.setSuccess(false);
            response.setShowToast(false);
        }
        return new ResponseEntity<>( response , response.getStatus());
    }

    @RequestMapping(path = "/signup", method = RequestMethod.PUT)
    public ResponseEntity<ApiResponse<User>> signup(@RequestBody SignUpRequest signUpRequest) throws MessagingException {
        ApiResponse<User> response = new ApiResponse<>();
        Company company = companyController.isCompanyExist(signUpRequest.getCompany());
        if(company == null){
            response.setStatus(HttpStatus.CONFLICT);
            response.setMessageLabel("auth_signup_used_company_name_error_message");
            response.setData(null);
            response.setSuccess(false);
        }else {
            response = userController.addUser(signUpRequest);
        }
        return new ResponseEntity<>( response , response.getStatus());
    }

    @RequestMapping(path = "/resetpasswordconfirmation", method = RequestMethod.POST)
    public ResponseEntity<ApiResponse<ResetPasswordResponse>> resetPasswordConfirmation(@RequestParam("email") String email) throws MessagingException {
        ApiResponse<ResetPasswordResponse> response = userController.resetPasswordMailConfirmation(email);
        return new ResponseEntity<>( response , response.getStatus());
    }

    @RequestMapping(path = "/resetpasswordcodecheck", method = RequestMethod.GET)
    public ResponseEntity<ApiResponse<User>>  resetPasswordCodeCheck(@RequestParam("code") String code, @RequestParam("email") String email, HttpServletResponse httpResponse) {
        ApiResponse<User> response = userController.resetPasswordCodeCheck(code,email,httpResponse);
        if(response.getData() != null) {
            Company company = response.getData().getCompany();
            String jwtToken = jwtUtil.generateToken(response.getData().getEmail(), company);
            Cookie cookie = new Cookie("jwt", jwtToken);
            cookie.setHttpOnly(true); // protect from JavaScript
            cookie.setSecure(frontBasePath.startsWith("https://"));   // only HTTPS
            cookie.setPath("/");      // available for the whole domain
            if (company.getSettings() != null) {
                cookie.setMaxAge(company.getSettings().getJwtTokenExpireIn() * 60);
            } else {
                cookie.setMaxAge((int) (EXPIRATION * 60));
            }
            httpResponse.addCookie(cookie);
        }
        return new ResponseEntity<>( response , response.getStatus());
    }

    @RequestMapping(path = "/changepassword", method = RequestMethod.POST)
    public ResponseEntity<ApiResponse<Boolean>> ChangePassword(@RequestBody ChangePasswordRequest changePasswordRequest,
                                                               HttpServletRequest request) {
        String token = jwtUtil.extractTokenFromCookie(request);
        ApiResponse<Boolean> response = new ApiResponse<>();
        if (token == null || !jwtUtil.validateToken(token)) {
            response.setData(null);
            response.setStatus(HttpStatus.UNAUTHORIZED);
            response.setSuccess(false);
            response.setMessageLabel("auth_profile_expired_error_message");
            response.setDoLogout(true);
        }else {
            response = userController.changePassword(changePasswordRequest);
        }
        return new ResponseEntity<>( response , response.getStatus());
    }

    @RequestMapping(path = "/settings", method = RequestMethod.GET)
    public ResponseEntity<ApiResponse<SharedSettings>> getSharedSettings() {
        ApiResponse<SharedSettings> response = new ApiResponse<>();
        response.setData(sharedSettings);
        response.setStatus(HttpStatus.OK);
        response.setSuccess(true);
        response.setShowToast(false);
        return new ResponseEntity<>( response , response.getStatus());
    }

//    @GetMapping("/login/check")
//    public ResponseEntity<?> checkAuth(HttpServletRequest request) {
//        String token = null;
//        if (request.getCookies() != null) {
//            for (Cookie cookie : request.getCookies()) {
//                if ("jwt".equals(cookie.getName())) {
//                    token = cookie.getValue();
//                }
//            }
//        }
//
//        if (token != null && jwtUtil.validateToken(token)) {
//            return ResponseEntity.ok().build();
//        } else {
//            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
//        }
//    }
    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletResponse response) {
        Cookie cookie = new Cookie("jwt", null);
        cookie.setHttpOnly(true);
        cookie.setSecure(frontBasePath.startsWith("https://"));
        cookie.setPath("/");
        cookie.setMaxAge(0); // delete cookie
        response.addCookie(cookie);
        return ResponseEntity.ok("Logged out");
    }

    @GetMapping("/login/check")
    public ResponseEntity<ApiResponse<LoginResponse>> getUserProfile(HttpServletRequest request) {
        ApiResponse<LoginResponse> response = new ApiResponse<>();
        String token = jwtUtil.extractTokenFromCookie(request);
        if (token == null || !jwtUtil.validateToken(token)) {
            response.setData(null);
            response.setStatus(HttpStatus.UNAUTHORIZED);
            response.setSuccess(false);
            response.setMessageLabel("auth_profile_expired_error_message");
            response.setDoLogout(true);
        }else{
            String email = jwtUtil.extractUsername(token);
            User user = userController.getUserByEmail(email);
            Company company = companyController.getMembersByUser(user);
            if(company == null){
                response.setData(null);
                response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR);
                response.setMessageLabel("error_status_INTERNAL_SERVER_ERROR");
                response.setSuccess(false);
            }else if(user == null){
                response.setData(null);
                response.setStatus(HttpStatus.UNAUTHORIZED);
                response.setSuccess(false);
                response.setMessageLabel("auth_profile_expired_error_message");
            }else{
                LoginResponse  loginResponse = new LoginResponse();
                loginResponse.setUser(user);
                loginResponse.setCompany(company);
                response.setData(loginResponse);
                response.setStatus(HttpStatus.OK);
                response.setSuccess(true);
                response.setShowToast(false);
            }
        }
        return new ResponseEntity<>( response , response.getStatus());
    }
}
