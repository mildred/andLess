#!/usr/bin/perl

use POSIX qw(strftime); 
$t = strftime "%b %e, %Y", localtime;
while(<>) {
	s/(built on|Дата сборки) \S+ \d+, \d+/$1 $t/;
	print;
}


