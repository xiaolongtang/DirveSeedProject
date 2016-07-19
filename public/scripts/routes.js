/**
 * Router Config
 * This is the router definition that defines all application routes.
 */
define(['angular', 'angular-ui-router'], function(angular) {
    'use strict';
    return angular.module('app.routes', ['ui.router']).config(['$stateProvider', '$urlRouterProvider', '$locationProvider', function($stateProvider, $urlRouterProvider, $locationProvider) {

        //Turn on or off HTML5 mode which uses the # hash
        $locationProvider.html5Mode(true).hashPrefix('!');

        /**
         * Router paths
         * This is where the name of the route is matched to the controller and view template.
         */
        $stateProvider
            .state('secure', {
                template: '<ui-view/>',
                abstract: true,
                resolve: {
                    authenticated: ['$q', 'PredixUserService', function ($q, predixUserService) {
                        var deferred = $q.defer();
                        predixUserService.isAuthenticated().then(function(userInfo){
                            deferred.resolve(userInfo);
                        }, function(){
                            deferred.reject({code: 'UNAUTHORIZED'});
                        });
                        return deferred.promise;
                    }]
                }
            })
            .state('dashboards', {
                parent: 'secure',
                url: '/dashboards',
                templateUrl: 'views/dashboards.html',
                controller: 'DashboardsCtrl'
            })
            .state('blankpage', {
                url: '/blankpage',
                templateUrl: 'views/blank-page.html'
            })
            .state('blanksubpage', {
                url: '/blanksubpage',
                templateUrl: 'views/blank-sub-page.html'
            })
            .state('lathestatus', {
                parent: 'secure',
                url: '/lathestatus',
                templateUrl: 'views/lathestatus.html',
                controller: 'LatheStatusCtrl'
            })
            .state('showlathe', {
                parent: 'secure',
                url: '/showlathe/:data',
                templateUrl: 'views/showlathe.html',
                controller: 'ShowLatheCtrl'
            })
            .state('voltage', {
                parent: 'secure',
                url: '/voltage',
                templateUrl: 'views/voltage.html',
                controller: 'VoltageCtrl'
            })
            .state('history', {
                parent: 'secure',
                url: '/history',
                templateUrl: 'views/history.html',
                controller: 'HistoryCtrl'
            })
            .state('assethistory', {
                parent: 'secure',
                url: '/assethistory',
                templateUrl: 'views/assethistory.html',
                controller: 'AssetHistoryCtrl'
            });


        $urlRouterProvider.otherwise(function ($injector) {
            var $state = $injector.get('$state');
            document.querySelector('px-app-nav').markSelected('/dashboards');
            $state.go('dashboards');
        });

    }]);
});
